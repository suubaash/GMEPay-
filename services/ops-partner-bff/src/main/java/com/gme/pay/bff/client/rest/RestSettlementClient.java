package com.gme.pay.bff.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.bff.client.SettlementClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Production {@link SettlementClient}. Talks to settlement-reconciliation over
 * HTTP via Spring 6 {@link RestClient}. Active when
 * {@code gmepay.settlement-reconciliation.client=rest}; otherwise the in-memory
 * {@link com.gme.pay.bff.client.stub.StubSettlementClient} wins so the BFF still
 * boots standalone for tests / local dev.
 *
 * <p>Endpoint mapping (settlement-reconciliation/SettlementController.java):
 * <ul>
 *   <li>{@code GET /v1/settlements} -> {@link #recent(String, int)}</li>
 * </ul>
 *
 * <p>The upstream {@code GET /v1/settlements} returns per-merchant settlement
 * summaries for a single date (defaulting to today when {@code date} is omitted).
 * It computes net (domestic, type 'N') / gross (international, type 'G') figures on
 * the fly; amounts are KRW integers. We map each row onto the BFF's
 * {@link SettlementBatchSummary} with a synthetic, stable {@code batchId} of
 * {@code merchantId-settlementDate-settlementType}, the merchant as the
 * {@code partnerId}, the KRW net amount, and {@code status=COMPLETED} (the computed
 * settlement is final).
 *
 * <p><b>Known limitation.</b> settlement-reconciliation exposes no per-batch detail
 * endpoint (no matched/unmatched line breakdown over HTTP) and no date-range query,
 * so {@link #detail(String)} returns {@code null} (controller -> 404, the Admin
 * drawer degrades gracefully) and {@link #recent} surfaces the current settlement
 * date only. Wiring batch-detail + date-range is a follow-up once
 * settlement-reconciliation persists settlement_batches/lines (audit P2).
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.settlement-reconciliation.client", havingValue = "rest")
public class RestSettlementClient implements SettlementClient {

    private static final Logger log = LoggerFactory.getLogger(RestSettlementClient.class);

    /** settlement-reconciliation computes settlement amounts in KRW. */
    private static final String SETTLEMENT_CURRENCY = "KRW";

    private final RestClient restClient;

    @Autowired
    public RestSettlementClient(
            @Value("${gmepay.settlement-reconciliation.base-url:http://settlement-reconciliation:8080}") String baseUrl) {
        this(RestClient.builder().baseUrl(baseUrl).build());
    }

    /** Package-private constructor for tests to inject a pre-built RestClient. */
    RestSettlementClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public List<SettlementBatchSummary> recent(String partnerId, int limit) {
        try {
            List<WireSettlement> upstream = restClient.get()
                    .uri("/v1/settlements")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<WireSettlement>>() {});
            if (upstream == null) {
                return List.of();
            }
            return upstream.stream()
                    .filter(r -> partnerId == null || partnerId.equals(r.merchantId()))
                    .limit(limit <= 0 ? Long.MAX_VALUE : limit)
                    .map(WireSettlement::toSummary)
                    .toList();
        } catch (RestClientResponseException e) {
            log.warn("settlement-reconciliation error on recent (status={}): {}", e.getStatusCode(), e.getMessage());
            return List.of();
        } catch (ResourceAccessException e) {
            log.warn("settlement-reconciliation unreachable on recent: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public SettlementBatchDetail detail(String batchId) {
        // No per-batch detail endpoint upstream yet (see class javadoc). The BFF
        // contract maps null -> HTTP 404 so the Admin drawer degrades gracefully.
        return null;
    }

    @Override
    public Integer openReconExceptions() {
        try {
            // ReConExceptionController: GET /v1/settlement/exceptions (singular), returns a
            // filterable list — the open count is the size of the OPEN-filtered list.
            List<WireException> open = restClient.get()
                    .uri("/v1/settlement/exceptions?exceptionStatus=OPEN")
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<WireException>>() {});
            return open == null ? null : open.size();
        } catch (RestClientResponseException e) {
            log.warn("settlement-reconciliation error on recon exceptions (status={}): {}",
                    e.getStatusCode(), e.getMessage());
            return null;
        } catch (ResourceAccessException e) {
            log.warn("settlement-reconciliation unreachable on recon exceptions: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public ReconRerunResult rerunRecon(String date, String actor, String reason) {
        try {
            // ReconRerunRequest's canonical field names are settlementDate/operatorId (not
            // date/actor) — sending the wrong names silently drops operator attribution.
            java.util.Map<String, String> body = new java.util.HashMap<>();
            if (date != null) {
                body.put("settlementDate", date);
            }
            if (actor != null) {
                body.put("operatorId", actor);
            }
            if (reason != null) {
                body.put("reason", reason);
            }
            WireRerun r = restClient.post()
                    .uri("/v1/settlements/recon/rerun")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(WireRerun.class);
            if (r == null) {
                return new ReconRerunResult("COMPLETED", null, null, null);
            }
            return new ReconRerunResult(
                    "COMPLETED",
                    r.totalMatched(),
                    r.totalExceptions(),
                    r.batchesRerun() == null ? null : "batchesRerun=" + r.batchesRerun());
        } catch (RestClientResponseException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatusCode.valueOf(e.getStatusCode().value()), e.getMessage());
        }
    }

    /** One row of ReConExceptionController's list response; only presence matters (we count). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireException(Long id) {}

    /** ReconRerunController's {@code ReconRerunResponse} wire shape. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireRerun(String operatorId, Integer batchesRerun, Integer totalMatched, Integer totalExceptions) {}

    /** settlement-reconciliation's {@code SettlementResponse} wire shape. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireSettlement(
            String merchantId,
            LocalDate settlementDate,
            char settlementType,
            int txnCount,
            BigDecimal grossTxnAmount,
            BigDecimal merchantFeeTotal,
            BigDecimal netSettlementAmount
    ) {
        SettlementBatchSummary toSummary() {
            String batchId = merchantId + "-" + settlementDate + "-" + settlementType;
            return new SettlementBatchSummary(
                    batchId,
                    merchantId,
                    settlementDate,
                    SETTLEMENT_CURRENCY,
                    netSettlementAmount,
                    "COMPLETED");
        }
    }
}
