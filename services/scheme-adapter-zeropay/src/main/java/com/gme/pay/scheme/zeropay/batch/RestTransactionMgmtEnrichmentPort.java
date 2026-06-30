package com.gme.pay.scheme.zeropay.batch;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST-backed {@link ZpBatchEnrichmentPort} that pulls real refund and settlement-value-date
 * data from transaction-management, mirroring the gating convention of
 * {@link com.gme.pay.scheme.zeropay.client.ZeroPaySchemeApiClient} (config-driven base URL, a
 * second package-private constructor for tests).
 *
 * <p>Bound endpoints (transaction-management):</p>
 * <ul>
 *   <li>{@code GET /v1/transactions/refunded?refundedOn=YYYY-MM-DD} (IR-1) → refund legs with the
 *       original scheme txnRef plus {@code refundAmountKrw}, {@code merchantId}, {@code qrCodeId}.</li>
 *   <li>{@code GET /v1/transactions/fx-committed?committedOn=YYYY-MM-DD} (IR-3) → committed
 *       transactions carrying the T+n {@code settlementDate} keyed by scheme txnRef.</li>
 * </ul>
 *
 * <p>Gated by {@code adapter.zeropay.enrichment.enabled} (default {@code false}). When disabled the
 * bean is not registered and {@link NoOpZpBatchEnrichmentPort} supplies empty maps. When enabled
 * but the upstream is unreachable or returns an error, every method logs and returns an empty map
 * so the batch run never fails and degrades to pre-enrichment behaviour (zero refund amount /
 * business-date value date). Calls never throw.</p>
 */
@Component
@ConditionalOnProperty(name = "adapter.zeropay.enrichment.enabled", havingValue = "true")
public class RestTransactionMgmtEnrichmentPort implements ZpBatchEnrichmentPort {

    private static final Logger log = LoggerFactory.getLogger(RestTransactionMgmtEnrichmentPort.class);

    private final RestClient restClient;

    /**
     * Primary constructor — wired by Spring with the configured transaction-management base URL.
     * {@code @Autowired} is required because of the package-private test constructor (Spring 6
     * rule: {@code @Autowired} on the {@code @Value} ctor when 2+ ctors exist).
     */
    @Autowired
    public RestTransactionMgmtEnrichmentPort(
            RestClient.Builder builder,
            @Value("${adapter.zeropay.enrichment.transaction-mgmt-base-url:http://localhost:8080}")
            String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /** Package-private test constructor — accepts a pre-built RestClient (e.g. MockRestServiceServer). */
    RestTransactionMgmtEnrichmentPort(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Map<String, RefundEnrichment> refundEnrichment(LocalDate businessDate) {
        List<RefundedTxnView> rows;
        try {
            rows = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/transactions/refunded")
                            .queryParam("refundedOn", businessDate)
                            .build())
                    .retrieve()
                    .body(REFUNDED_LIST);
        } catch (RuntimeException e) {
            log.warn("Refund enrichment unavailable for {} (non-fatal, using captured values): {}",
                    businessDate, e.getMessage());
            return Map.of();
        }
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<String, RefundEnrichment> out = new LinkedHashMap<>(rows.size());
        for (RefundedTxnView r : rows) {
            String key = firstNonBlank(r.refundSchemeTxnRef(), r.originalSchemeTxnRef(), r.txnRef());
            if (key == null) {
                continue;
            }
            out.put(key, new RefundEnrichment(r.refundAmountKrw(), r.merchantId(), r.qrCodeId()));
        }
        return out;
    }

    @Override
    public Map<String, LocalDate> settlementValueDates(LocalDate businessDate) {
        List<CommittedTxnView> rows;
        try {
            rows = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/transactions/fx-committed")
                            .queryParam("committedOn", businessDate)
                            .build())
                    .retrieve()
                    .body(COMMITTED_LIST);
        } catch (RuntimeException e) {
            log.warn("Settlement value-date enrichment unavailable for {} (non-fatal, using "
                    + "business date): {}", businessDate, e.getMessage());
            return Map.of();
        }
        if (rows == null || rows.isEmpty()) {
            return Map.of();
        }
        Map<String, LocalDate> out = new LinkedHashMap<>(rows.size());
        for (CommittedTxnView c : rows) {
            String key = firstNonBlank(c.schemeTxnRef(), c.txnRef());
            if (key != null && c.settlementDate() != null) {
                out.put(key, c.settlementDate());
            }
        }
        return out;
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Wire DTOs — consumer-owned shapes for the transaction-management projections.
    // Tolerant of extra/missing fields (@JsonIgnoreProperties) so the upstream can evolve.
    // -------------------------------------------------------------------------

    private static final org.springframework.core.ParameterizedTypeReference<List<RefundedTxnView>>
            REFUNDED_LIST = new org.springframework.core.ParameterizedTypeReference<>() {};

    private static final org.springframework.core.ParameterizedTypeReference<List<CommittedTxnView>>
            COMMITTED_LIST = new org.springframework.core.ParameterizedTypeReference<>() {};

    /** One refund leg from {@code GET /v1/transactions/refunded}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record RefundedTxnView(
            String txnRef,
            String refundSchemeTxnRef,
            String originalSchemeTxnRef,
            BigDecimal refundAmountKrw,
            String merchantId,
            String qrCodeId) {
    }

    /** One committed transaction from {@code GET /v1/transactions/fx-committed}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record CommittedTxnView(
            String txnRef,
            String schemeTxnRef,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate settlementDate) {
    }
}
