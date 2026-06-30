package com.gme.pay.settlement.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
import java.time.format.DateTimeParseException;
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
            RefundedTransactionResponse[] body =
                    restTemplate.getForObject(url, RefundedTransactionResponse[].class);
            if (body == null) {
                return List.of();
            }
            List<RefundLeg> legs = new ArrayList<>(body.length);
            for (RefundedTransactionResponse r : body) {
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

    private static RefundLeg toRefundLeg(RefundedTransactionResponse r, LocalDate fallbackDate) {
        BigDecimal amount = parseDecimalOrZero(r.refundAmount(), "refundAmount", r.refundTxnRef());
        // Carry the magnitude positive — the netting layer subtracts it from the merchant's gross.
        amount = amount.abs();
        LocalDate refundedOn = parseDateOrNull(r.refundedOn());
        if (refundedOn == null) {
            refundedOn = fallbackDate;
        }
        OffsetDateTime refundedAt = parseInstantOrNull(r.refundedAt(), r.refundTxnRef());
        return new RefundLeg(
                r.refundTxnRef(),
                r.originalTxnRef(),
                r.merchantId(),
                amount,
                refundedOn,
                refundedAt);
    }

    private static BigDecimal parseDecimalOrZero(String value, String field, String txnRef) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            log.warn("Unparseable {} '{}' on refund {} — treating as 0", field, value, txnRef);
            return BigDecimal.ZERO;
        }
    }

    private static LocalDate parseDateOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static OffsetDateTime parseInstantOrNull(String value, String txnRef) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException e) {
            log.warn("Unparseable refundedAt '{}' on refund {} — null", value, txnRef);
            return null;
        }
    }

    /**
     * One refund leg item from {@code GET /v1/transactions/refunded}. Field names must match
     * transaction-mgmt's projection verbatim (Jackson binds by name; a mismatch silently nulls).
     * {@code @JsonIgnoreProperties(ignoreUnknown = true)} keeps unknown future fields harmless.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefundedTransactionResponse(
            String refundTxnRef,
            String originalTxnRef,
            String merchantId,
            String refundAmount,
            String refundCcy,
            String refundedOn,
            String refundedAt) {
    }
}
