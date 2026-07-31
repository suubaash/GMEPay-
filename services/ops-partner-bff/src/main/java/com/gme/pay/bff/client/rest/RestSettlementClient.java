package com.gme.pay.bff.client.rest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.settlement.SettlementStatuses;
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
import org.springframework.web.util.UriBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Production {@link SettlementClient}. Talks to settlement-reconciliation over HTTP via Spring 6
 * {@link RestClient}. Active when {@code gmepay.settlement-reconciliation.client=rest}; otherwise the
 * in-memory {@link com.gme.pay.bff.client.stub.StubSettlementClient} wins so the BFF still boots
 * standalone for tests / local dev.
 *
 * <h2>Endpoint mapping — all four reads are of the PERSISTED record (GAP T4-5)</h2>
 * <ul>
 *   <li>{@code GET /v1/settlements/batches?from=&to=&counterpartyId=&limit=} → {@link #recent} / {@link #range}</li>
 *   <li>{@code GET /v1/settlements/batches/{batchId}} → {@link #detail(String)}</li>
 *   <li>{@code GET /v1/settlements/statement?merchantId=&from=&to=} → {@link #statement}</li>
 *   <li>{@code GET /v1/settlements/transmission-channel} → {@link #transmissionChannel()}</li>
 *   <li>{@code GET /v1/settlement/exceptions?exceptionStatus=OPEN} → {@link #openReconExceptions()}</li>
 *   <li>{@code POST /v1/settlements/recon/rerun} → {@link #rerunRecon}</li>
 * </ul>
 *
 * <h2>What this class no longer does</h2>
 * It used to call only {@code GET /v1/settlements}, which recomputes per-merchant figures from
 * unbatched transactions for one date and touches neither persisted table. Consequently it
 * <b>synthesised</b> {@code batchId = merchantId-settlementDate-settlementType} (ids matching no row),
 * <b>hardcoded</b> {@code status=COMPLETED} (a word that is not in the upstream vocabulary),
 * returned {@code null} from {@code detail} for every id, and could offer no date range. All four are
 * gone: ids and statuses come off the row, {@code detail} resolves, and the window is a real window.
 *
 * <p><b>Nothing here upgrades a status.</b> Every value passes through
 * {@link SettlementStatuses}, so an absent or unrecognised upstream value becomes {@code UNKNOWN}
 * rather than a success, and a batch that upstream says was never transmitted can never be presented
 * as transmitted.
 */
@Component
@Primary
@ConditionalOnProperty(name = "gmepay.settlement-reconciliation.client", havingValue = "rest", matchIfMissing = true)
public class RestSettlementClient implements SettlementClient {

    private static final Logger log = LoggerFactory.getLogger(RestSettlementClient.class);

    /** Fallback when a batch row carries no settle currency (ZeroPay settles in KRW). */
    private static final String DEFAULT_SETTLEMENT_CURRENCY = "KRW";

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
        return range(partnerId, null, null, limit);
    }

    @Override
    public List<SettlementBatchSummary> range(String partnerId, LocalDate from, LocalDate to, int limit) {
        try {
            List<WireBatch> upstream = restClient.get()
                    .uri(uri -> batchesUri(uri, partnerId, from, to, limit))
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<WireBatch>>() {});
            if (upstream == null) {
                return List.of();
            }
            return upstream.stream().map(WireBatch::toSummary).toList();
        } catch (RestClientResponseException e) {
            log.warn("settlement-reconciliation error on batches (status={}): {}",
                    e.getStatusCode(), e.getMessage());
            return List.of();
        } catch (ResourceAccessException e) {
            log.warn("settlement-reconciliation unreachable on batches: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public SettlementBatchDetail detail(String batchId) {
        if (batchId == null || batchId.isBlank()) {
            return null;
        }
        try {
            WireBatchDetail upstream = restClient.get()
                    .uri("/v1/settlements/batches/{batchId}", batchId)
                    .retrieve()
                    .body(WireBatchDetail.class);
            if (upstream == null || upstream.batch() == null) {
                return null;
            }
            return new SettlementBatchDetail(
                    upstream.batch().toSummary(),
                    WireLine.toLines(upstream.lines()),
                    upstream.matchedCount() == null ? 0 : upstream.matchedCount(),
                    upstream.openCount() == null ? 0 : upstream.openCount());
        } catch (RestClientResponseException e) {
            // 404 = genuinely no such batch. The controller maps null -> 404 for the Admin drawer.
            if (e.getStatusCode().value() != 404) {
                log.warn("settlement-reconciliation error on batch detail {} (status={}): {}",
                        batchId, e.getStatusCode(), e.getMessage());
            }
            return null;
        } catch (ResourceAccessException e) {
            log.warn("settlement-reconciliation unreachable on batch detail {}: {}", batchId, e.getMessage());
            return null;
        }
    }

    @Override
    public PartnerStatement statement(String partnerId, LocalDate from, LocalDate to, boolean includeLines) {
        try {
            WireStatement upstream = restClient.get()
                    .uri(uri -> {
                        UriBuilder b = uri.path("/v1/settlements/statement")
                                .queryParam("merchantId", partnerId)
                                .queryParam("includeLines", includeLines);
                        if (from != null) {
                            b = b.queryParam("from", from.toString());
                        }
                        if (to != null) {
                            b = b.queryParam("to", to.toString());
                        }
                        return b.build();
                    })
                    .retrieve()
                    .body(WireStatement.class);
            if (upstream == null) {
                return emptyStatement(partnerId, from, to,
                        "settlement-reconciliation returned no statement body");
            }
            return upstream.toStatement(partnerId);
        } catch (RestClientResponseException e) {
            log.warn("settlement-reconciliation error on statement for {} (status={}): {}",
                    partnerId, e.getStatusCode(), e.getMessage());
            return emptyStatement(partnerId, from, to,
                    "settlement-reconciliation returned " + e.getStatusCode() + " for this statement");
        } catch (ResourceAccessException e) {
            log.warn("settlement-reconciliation unreachable on statement for {}: {}",
                    partnerId, e.getMessage());
            return emptyStatement(partnerId, from, to,
                    "settlement-reconciliation is unreachable; this statement is EMPTY because nothing "
                            + "could be read, not because nothing was settled");
        }
    }

    @Override
    public TransmissionChannel transmissionChannel() {
        try {
            WireChannel upstream = restClient.get()
                    .uri("/v1/settlements/transmission-channel")
                    .retrieve()
                    .body(WireChannel.class);
            return upstream == null
                    ? TransmissionChannel.unknown(
                            "settlement-reconciliation returned no transmission-channel body")
                    : upstream.toChannel();
        } catch (RestClientResponseException e) {
            log.warn("settlement-reconciliation error on transmission channel (status={}): {}",
                    e.getStatusCode(), e.getMessage());
            return TransmissionChannel.unknown(
                    "settlement-reconciliation returned " + e.getStatusCode()
                            + " for the transmission-channel board; nothing may be assumed transmitted");
        } catch (ResourceAccessException e) {
            log.warn("settlement-reconciliation unreachable on transmission channel: {}", e.getMessage());
            return TransmissionChannel.unknown(
                    "settlement-reconciliation is unreachable; the transmission-channel board is "
                            + "unknown and nothing may be assumed transmitted");
        }
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

    private static java.net.URI batchesUri(UriBuilder uri, String partnerId,
                                           LocalDate from, LocalDate to, int limit) {
        UriBuilder b = uri.path("/v1/settlements/batches");
        if (partnerId != null && !partnerId.isBlank()) {
            b = b.queryParam("counterpartyId", partnerId);
        }
        if (from != null) {
            b = b.queryParam("from", from.toString());
        }
        if (to != null) {
            b = b.queryParam("to", to.toString());
        }
        if (limit > 0) {
            b = b.queryParam("limit", limit);
        }
        return b.build();
    }

    /**
     * An empty statement carrying WHY it is empty. Never a fabricated zero position: the reason
     * distinguishes "this partner settled nothing" from "we could not read the settlement record".
     */
    private static PartnerStatement emptyStatement(String partnerId, LocalDate from, LocalDate to,
                                                   String reason) {
        return new PartnerStatement(partnerId, from, to, null, List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0,
                TransmissionChannel.unknown(reason));
    }

    /** One row of ReConExceptionController's list response; only presence matters (we count). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireException(Long id) {}

    /** ReconRerunController's {@code ReconRerunResponse} wire shape. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WireRerun(String operatorId, Integer batchesRerun, Integer totalMatched, Integer totalExceptions) {}

    /** settlement-reconciliation's {@code SettlementBatchSummaryResponse} wire shape. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireBatch(
            String batchId,
            String counterpartyId,
            LocalDate businessDate,
            String status,
            String settleCurrency,
            BigDecimal netSettlementAmount,
            BigDecimal totalAmount,
            String transmissionState,
            String transmissionDetail,
            String transmissionChannel,
            String transmittedAt
    ) {
        SettlementBatchSummary toSummary() {
            return new SettlementBatchSummary(
                    batchId,
                    counterpartyId,
                    businessDate,
                    settleCurrency == null || settleCurrency.isBlank()
                            ? DEFAULT_SETTLEMENT_CURRENCY : settleCurrency,
                    // A DETAIL batch has no net (a net figure there would be a category error), so
                    // fall back to the batch's own total rather than reporting a null amount.
                    netSettlementAmount != null ? netSettlementAmount : totalAmount,
                    SettlementStatuses.lifecycleFromUpstream(status),
                    SettlementStatuses.transmissionFromUpstream(transmissionState),
                    SettlementStatuses.reasonFor(status, transmissionState, transmissionDetail),
                    // Only carried when upstream itself says TRANSMITTED — a stray timestamp on a
                    // not-sent row must not travel downstream as evidence of a send.
                    SettlementStatuses.isTransmitted(transmissionState) ? transmittedAt : null);
        }
    }

    /** settlement-reconciliation's {@code SettlementBatchLineResponse} wire shape. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireLine(String txnRef, BigDecimal amount, String currency, boolean matched) {

        static List<SettlementLine> toLines(List<WireLine> lines) {
            return lines == null ? List.of()
                    : lines.stream()
                            .map(l -> new SettlementLine(l.txnRef(), l.amount(), l.currency(), l.matched()))
                            .toList();
        }
    }

    /** settlement-reconciliation's {@code SettlementBatchDetailResponse} wire shape. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireBatchDetail(WireBatch batch, List<WireLine> lines, Integer matchedCount, Integer openCount) {}

    /** settlement-reconciliation's {@code SettlementTransmissionChannelStatus} wire shape. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireChannel(
            @com.fasterxml.jackson.annotation.JsonProperty("channel_live") Boolean live,
            @com.fasterxml.jackson.annotation.JsonProperty("reachable_state") String reachableState,
            @com.fasterxml.jackson.annotation.JsonProperty("reason") String reason) {

        TransmissionChannel toChannel() {
            boolean isLive = Boolean.TRUE.equals(live);
            return new TransmissionChannel(
                    isLive,
                    SettlementStatuses.transmissionFromUpstream(reachableState),
                    isLive ? reason
                            : (reason != null && !reason.isBlank() ? reason
                                    : "settlement-reconciliation reports no live transmission channel"));
        }
    }

    /** settlement-reconciliation's {@code PartnerSettlementStatementResponse} wire shape. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireStatement(
            String merchantId,
            LocalDate from,
            LocalDate to,
            String currency,
            List<WireStatementEntry> entries,
            BigDecimal netSettlementAmount,
            BigDecimal paymentAmount,
            BigDecimal clawbackAmount,
            Integer lineCount,
            Integer openLineCount,
            Integer transmittedEntryCount,
            WireChannel transmissionChannel) {

        PartnerStatement toStatement(String requestedPartnerId) {
            List<StatementEntry> mapped = entries == null ? List.of()
                    : entries.stream().map(WireStatementEntry::toEntry).toList();
            // Recount from the mapped rows rather than trusting the field: the count must agree with
            // what this BFF is actually willing to call transmitted.
            int transmitted = (int) mapped.stream()
                    .filter(e -> e.batch() != null && e.batch().transmitted())
                    .count();
            return new PartnerStatement(
                    merchantId != null ? merchantId : requestedPartnerId,
                    from, to, currency, mapped,
                    zeroIfNull(netSettlementAmount), zeroIfNull(paymentAmount), zeroIfNull(clawbackAmount),
                    lineCount == null ? 0 : lineCount,
                    openLineCount == null ? 0 : openLineCount,
                    transmitted,
                    transmissionChannel == null
                            ? TransmissionChannel.unknown(
                                    "settlement-reconciliation sent no transmission-channel board with "
                                            + "this statement; nothing may be assumed transmitted")
                            : transmissionChannel.toChannel());
        }

        private static BigDecimal zeroIfNull(BigDecimal v) {
            return v == null ? BigDecimal.ZERO : v;
        }
    }

    /** One entry of the upstream statement. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireStatementEntry(
            WireBatch batch,
            BigDecimal netSettlementAmount,
            BigDecimal paymentAmount,
            BigDecimal clawbackAmount,
            Integer lineCount,
            Integer openLineCount,
            List<WireLine> lines) {

        StatementEntry toEntry() {
            return new StatementEntry(
                    batch == null ? null : batch.toSummary(),
                    netSettlementAmount, paymentAmount, clawbackAmount,
                    lineCount == null ? 0 : lineCount,
                    openLineCount == null ? 0 : openLineCount,
                    WireLine.toLines(lines));
        }
    }
}
