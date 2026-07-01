package com.gme.pay.settlement.client;

import com.gme.pay.contracts.RefundedTransactionView;
import com.gme.pay.settlement.port.RefundedTransactionPort;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP client for transaction-mgmt's Phase-2 refund-date query
 * {@code GET /v1/transactions/refunded?refundedOn=YYYY-MM-DD} (shared contract, commit 5dbafd5).
 *
 * <p>Gated by {@code gmepay.clients.transaction-mgmt.refunded.enabled=true} ({@code @Primary} +
 * {@code @ConditionalOnProperty}), mirroring the rest-client gating used elsewhere in the fleet
 * (e.g. api-gateway {@code gmepay.config-registry.client=rest}). When disabled — the default in
 * dev/test where transaction-mgmt is not running — the in-process
 * {@link FixtureRefundedTransactionAdapter} wins. Never reads transaction-mgmt's DB directly.
 *
 * <p>Drives cross-date refund claw-back netting (settlement IR-1): each row carries the
 * <em>original</em> payment txnRef so a prior-day refund nets back to the original credit window.
 *
 * <p>Spring 6 two-constructor rule: the container-wired ctor carries {@link Autowired}; the other is
 * a package-private test helper taking a pre-built {@link RestTemplate} (e.g. MockRestServiceServer).
 */
@Primary
@Component
@ConditionalOnProperty(name = "gmepay.clients.transaction-mgmt.refunded.enabled", havingValue = "true")
public class RestRefundedTransactionClient implements RefundedTransactionPort {

    private static final Logger log = LoggerFactory.getLogger(RestRefundedTransactionClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    @Autowired
    public RestRefundedTransactionClient(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${gmepay.clients.transaction-mgmt.base-url:http://transaction-mgmt:8082}") String baseUrl) {
        this.restTemplate = restTemplateBuilder.build();
        this.baseUrl = baseUrl;
    }

    /** Test helper — pre-built RestTemplate (e.g. backed by MockRestServiceServer). */
    RestRefundedTransactionClient(RestTemplate restTemplate, String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    @Override
    public List<RefundLeg> findRefundedOn(LocalDate refundedOn) {
        String url = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/v1/transactions/refunded")
                .queryParam("refundedOn", refundedOn)
                .toUriString();
        log.debug("GET {}", url);
        try {
            RefundedTransactionView[] body =
                    restTemplate.getForObject(url, RefundedTransactionView[].class);
            if (body == null) {
                return List.of();
            }
            List<RefundLeg> legs = new ArrayList<>(body.length);
            for (RefundedTransactionView r : body) {
                legs.add(toRefundLeg(r, refundedOn));
            }
            log.debug("Fetched {} refund legs refundedOn={}", legs.size(), refundedOn);
            return legs;
        } catch (Exception e) {
            // Fail soft: a transaction-mgmt hiccup must not abort the settlement run. Returning an
            // empty list means the cross-date claw-back simply does not net this run; ops re-run later.
            log.warn("transaction-mgmt /v1/transactions/refunded unavailable for {} ({}) — no cross-date "
                    + "refund netting this run", refundedOn, e.toString());
            return List.of();
        }
    }

    /**
     * Map one canonical {@link RefundedTransactionView} row to a {@link RefundLeg}. The view uses the
     * PRODUCER's field names ({@code txnRef}/{@code originalPaymentTxnRef}/{@code refundAmountKrw}/
     * {@code refundedAt}), so the original-payment ref and KRW amount now bind to REAL values — the prior
     * ad-hoc record bound {@code originalTxnRef}/{@code refundAmount} which never matched the wire, leaving
     * every refund leg silently null and the cross-date claw-back netting a no-op.
     */
    private static RefundLeg toRefundLeg(RefundedTransactionView r, LocalDate fallbackDate) {
        // Carry the magnitude positive — the netting layer subtracts it from the merchant's gross.
        BigDecimal amount = (r.refundAmountKrw() == null ? BigDecimal.ZERO : r.refundAmountKrw()).abs();
        // Prefer the producer's settlement value date; else the refund instant's date; else the query date.
        OffsetDateTime refundedAt =
                r.refundedAt() == null ? null : r.refundedAt().atOffset(ZoneOffset.UTC);
        LocalDate refundedOn = r.settlementDate();
        if (refundedOn == null && refundedAt != null) {
            refundedOn = refundedAt.toLocalDate();
        }
        if (refundedOn == null) {
            refundedOn = fallbackDate;
        }
        return new RefundLeg(
                r.txnRef(),
                r.originalPaymentTxnRef(),
                r.merchantId(),
                amount,
                refundedOn,
                refundedAt);
    }
}
