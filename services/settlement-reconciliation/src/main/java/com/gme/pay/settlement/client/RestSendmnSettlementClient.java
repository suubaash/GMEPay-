package com.gme.pay.settlement.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.settlement.corridor.SchemeSettlementRecord;
import com.gme.pay.settlement.port.SchemeSettlementPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP implementation of {@link SchemeSettlementPort} for SENDMN — leg (c) of the three-way tie-out.
 *
 * <p>Reads the SendMN adapter's read-only
 * {@code GET /internal/scheme/sendmn/settlement/daily?date=YYYY-MM-DD}: the payments the adapter
 * recorded SendMN as confirming that day, each with the SendMN-<b>registered</b> rate its Confirm
 * used and the resulting USD {@code SETTLEMENT_AMOUNT} GME owes SendMN.
 *
 * <p><b>Not a partner statement.</b> These are GME's own adapter-side records. SendMN's actual recon
 * file format is an unresolved external question (plan gate O4) and is deliberately not modelled
 * here — see {@code SchemeReconFeedParser} for where it plugs in when it arrives.
 *
 * <p>Money and timestamps are read as strings and parsed defensively: one malformed row must not
 * abort the day. Transport failure yields an empty list (logged at ERROR, never thrown) so the run
 * degrades into visible MISSING_SCHEME breaks rather than dying.
 */
@Component
public class RestSendmnSettlementClient implements SchemeSettlementPort {

    private static final Logger log = LoggerFactory.getLogger(RestSendmnSettlementClient.class);

    /** Upper-case scheme code this client speaks for. */
    public static final String SCHEME = "SENDMN";

    private final RestTemplate restTemplate;
    private final String baseUrl;

    /** Primary constructor — wired by Spring (two constructors ⇒ {@code @Autowired} required). */
    @Autowired
    public RestSendmnSettlementClient(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${gmepay.scheme-adapters.SENDMN.base-url:http://scheme-adapter-sendmn:8093}") String baseUrl) {
        this.restTemplate = restTemplateBuilder.build();
        this.baseUrl = baseUrl;
    }

    /** Package-private constructor used by tests that supply a pre-built {@link RestTemplate}. */
    RestSendmnSettlementClient(RestTemplate restTemplate, String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    @Override
    public String scheme() {
        return SCHEME;
    }

    @Override
    public List<SchemeSettlementRecord> confirmedOn(LocalDate settlementDate) {
        String url = UriComponentsBuilder
                .fromHttpUrl(baseUrl + "/internal/scheme/sendmn/settlement/daily")
                .queryParam("date", settlementDate)
                .toUriString();

        DailySettlementView view;
        try {
            view = restTemplate.getForObject(url, DailySettlementView.class);
        } catch (Exception e) {
            log.error("scheme-adapter-sendmn unreachable while reading confirmed settlements for {}: {}"
                            + " — the corridor tie-out for this date is INCOMPLETE",
                    settlementDate, e.getMessage());
            return List.of();
        }
        if (view == null || view.rows() == null) {
            return List.of();
        }

        List<SchemeSettlementRecord> records = new ArrayList<>(view.rows().size());
        for (Row row : view.rows()) {
            records.add(new SchemeSettlementRecord(
                    row.hubReference(),
                    row.txTokenNo(),
                    row.merchantId(),
                    row.localCurCode(),
                    parseDecimalOrNull(row.localAmount(), "localAmount", row.txTokenNo()),
                    parseDecimalOrNull(row.fxUsdBuyRate(), "fxUsdBuyRate", row.txTokenNo()),
                    row.settlementCurCode(),
                    parseDecimalOrNull(row.settlementAmount(), "settlementAmount", row.txTokenNo()),
                    row.status(),
                    parseInstantOrNull(row.createdAt(), row.txTokenNo())));
        }
        log.debug("corridor leg(c): {} SENDMN-confirmed payments for {}", records.size(), settlementDate);
        return records;
    }

    private static Instant parseInstantOrNull(String value, String ref) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value.trim());
        } catch (Exception e) {
            try {
                return java.time.OffsetDateTime.parse(value.trim()).toInstant();
            } catch (Exception e2) {
                log.warn("unparseable adapter timestamp '{}' on {} — treated as absent", value, ref);
                return null;
            }
        }
    }

    private static BigDecimal parseDecimalOrNull(String value, String field, String ref) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            log.warn("unparseable adapter {} '{}' on {} — treated as absent", field, value, ref);
            return null;
        }
    }

    /**
     * Wire shape of the adapter's {@code DailySettlementResponse}. Read as strings (money is emitted
     * as decimal strings per MONEY_CONVENTION) and unknown fields ignored so an additive adapter
     * change cannot break the read.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DailySettlementView(
            String date,
            String status,
            String localCurCode,
            String settlementCurCode,
            String latestRegisteredRate,
            Integer count,
            List<Row> rows) {}

    /** One confirmed payment as the adapter reports it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(
            String hubReference,
            String txTokenNo,
            String merchantId,
            String localCurCode,
            String localAmount,
            String fxTickerNo,
            String fxUsdBuyRate,
            String settlementCurCode,
            String settlementAmount,
            String status,
            String paymentNo,
            String paymentReceiptNo,
            String createdAt,
            String updatedAt) {}
}
