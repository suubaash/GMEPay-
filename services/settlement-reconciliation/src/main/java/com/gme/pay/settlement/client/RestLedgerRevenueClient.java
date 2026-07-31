package com.gme.pay.settlement.client;

import com.gme.pay.contracts.RevenueSummaryView;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import com.gme.pay.settlement.port.LedgerRevenuePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * HTTP implementation of {@link LedgerRevenuePort} — calls revenue-ledger's canonical
 * <strong>GET /v1/revenue?partnerId={long}&amp;startDate={ISO}&amp;endDate={ISO}</strong>
 * (RevenueController) and binds the shared {@link RevenueSummaryView} (lib-api-contracts), whose
 * money fields ride as decimal STRINGs per {@code docs/MONEY_CONVENTION.md}.
 *
 * <p><b>Contract adaptation.</b> The upstream endpoint has no date-only form — {@code partnerId}
 * (numeric) is required and the window is {@code startDate}/{@code endDate}. For a single business
 * date we pass {@code startDate == endDate == date}, and resolve the numeric partner ids from the
 * date's own {@code settlement_batches} rows ({@link SettlementBatchEntity#getPartnerIdNew()}, the
 * V004 BIGINT surrogate FK). The per-partner {@code totalServiceChargeAmount} figures are summed —
 * the ledger's recognised merchant-fee revenue for the date, the counterpart of the batches'
 * {@code merchant_fee_total}.
 *
 * <p><b>Fail-soft to {@code null}</b> (= "ledger figure unavailable", never a fake zero) when:
 * revenue-ledger is unreachable, no batch for the date carries a numeric partner id yet (legacy
 * rows leave {@code partner_id_new} null during the Expand phase), or a non-zero fee is reported in
 * a currency other than KRW (a cent-for-cent KRW comparison would be meaningless). The tie-out
 * report stays useful without the ledger leg.
 *
 * <p>Gated by {@code gmepay.clients.revenue-ledger.enabled=true} ({@code @Primary} +
 * {@code @ConditionalOnProperty}), the SAME existing gate + base-url as
 * {@link RestRoundingResidualClient} — one downstream, one switch. When disabled (dev/test default)
 * the in-process {@link FixtureLedgerRevenueAdapter} wins. Never reads revenue-ledger's DB.
 *
 * <p>Spring 6 two-constructor rule: the container-wired ctor carries {@link Autowired}; the other
 * is a package-private test helper taking a pre-built {@link RestTemplate}.
 */
@Primary
@Component
@ConditionalOnProperty(name = "gmepay.clients.revenue-ledger.enabled", havingValue = "true")
public class RestLedgerRevenueClient implements LedgerRevenuePort {

    private static final Logger log = LoggerFactory.getLogger(RestLedgerRevenueClient.class);

    /** ZeroPay settles merchant fees in KRW; the tie-out compares in KRW only. */
    private static final String TIE_OUT_CCY = "KRW";

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final SettlementBatchRepository batchRepository;

    @Autowired
    public RestLedgerRevenueClient(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${gmepay.clients.revenue-ledger.base-url:http://revenue-ledger:8084}") String baseUrl,
            SettlementBatchRepository batchRepository) {
        this.restTemplate = restTemplateBuilder.build();
        this.baseUrl = baseUrl;
        this.batchRepository = batchRepository;
    }

    /** Test helper — pre-built RestTemplate (e.g. backed by MockRestServiceServer). */
    RestLedgerRevenueClient(RestTemplate restTemplate, String baseUrl,
                            SettlementBatchRepository batchRepository) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.batchRepository = batchRepository;
    }

    @Override
    public BigDecimal revenueFor(LocalDate date) {
        // Numeric partner ids come from the date's own batches (V004 surrogate FK). Distinct,
        // insertion-ordered so a multi-partner day queries each partner exactly once.
        Set<Long> partnerIds = batchRepository.findByBusinessDate(date).stream()
                .map(SettlementBatchEntity::getPartnerIdNew)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (partnerIds.isEmpty()) {
            log.warn("tie-out ledger leg unavailable for {}: no batch carries a numeric partner id "
                    + "(partner_id_new) to query GET /v1/revenue with", date);
            return null;
        }

        BigDecimal total = BigDecimal.ZERO;
        for (Long partnerId : partnerIds) {
            String url = UriComponentsBuilder.fromHttpUrl(baseUrl + "/v1/revenue")
                    .queryParam("partnerId", partnerId)
                    .queryParam("startDate", date)
                    .queryParam("endDate", date)
                    .toUriString();
            RevenueSummaryView view;
            try {
                view = restTemplate.getForObject(url, RevenueSummaryView.class);
            } catch (Exception e) {
                log.warn("revenue-ledger GET /v1/revenue failed for partnerId={} date={} ({}) — "
                        + "tie-out will report ledgerAvailable=false", partnerId, date, e.toString());
                return null;
            }
            if (view == null || view.totalServiceChargeAmount() == null) {
                continue;   // no fee rows for this partner in the window — contributes zero
            }
            BigDecimal fee = view.totalServiceChargeAmount();
            // A non-zero fee in a non-KRW currency cannot be compared cent-for-cent against the
            // KRW settlement fee total; report the ledger leg as unavailable rather than guess FX.
            if (fee.signum() != 0 && !TIE_OUT_CCY.equals(view.serviceChargeCcy())) {
                log.warn("revenue-ledger reports partnerId={} date={} service charge {} {} — not KRW, "
                        + "tie-out ledger leg unavailable", partnerId, date, fee.toPlainString(),
                        view.serviceChargeCcy());
                return null;
            }
            total = total.add(fee);
        }
        return total;
    }
}
