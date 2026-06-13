package com.gme.pay.registry.web;

import com.gme.pay.contracts.AuditEntryView;
import com.gme.pay.contracts.PageView;
import com.gme.pay.registry.audit.AuditLogEntity;
import com.gme.pay.registry.audit.AuditLogRepository;
import com.gme.pay.registry.audit.AuditLogService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Regulator-facing audit log read endpoint (Slice 2 agent 2C.1).
 *
 * <h2>Contract</h2>
 *
 * <pre>
 * GET /v1/audit?aggregateType=partner&amp;aggregateId={code}&amp;page=0&amp;size=20
 * </pre>
 *
 * <p>Returns a {@link PageView} of {@link AuditEntryView} rows ordered
 * {@code recorded_at DESC} (newest first) for the requested aggregate. Each
 * page includes a {@code chainValid} flag (inside every row) computed via
 * {@link AuditLogService#verifyChain(String, String)}: {@code true} when the
 * full hash chain for the aggregate is intact at the time of the request,
 * {@code false} when any row has been tampered with.
 *
 * <h2>What is NOT exposed</h2>
 *
 * <p>Raw hash bytes ({@code prev_hash}, {@code row_hash}) are intentionally
 * stripped before the response leaves this service. The regulator UI only
 * needs the {@code chainValid} signal and the human-readable event data;
 * exposing raw hashes would couple the UI to hash-algorithm details (SHA-256
 * 32-byte output) that are subject to change in Slice 8's hardening pass.
 *
 * <h2>Security note</h2>
 *
 * <p>This endpoint is intended to be called only by the BFF's
 * {@code AuditTrailController} (which adds Keycloak OIDC role-gating at the
 * BFF layer). It is exposed at {@code /v1/audit} rather than under
 * {@code /v1/admin/*} to keep the URL namespace flat on config-registry and
 * match the existing {@code /v1/partners} and {@code /v1/rules} convention.
 */
@RestController
@RequestMapping("/v1/audit")
public class AuditLogController {

    /** Default page size when the caller omits {@code size}. */
    static final int DEFAULT_PAGE_SIZE = 20;

    /** Hard upper cap on page size to bound the memory footprint per request. */
    static final int MAX_PAGE_SIZE = 200;

    private final AuditLogRepository repository;
    private final AuditLogService auditLogService;

    public AuditLogController(AuditLogRepository repository, AuditLogService auditLogService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
    }

    /**
     * List audit entries for a specific aggregate, newest first.
     *
     * @param aggregateType the aggregate kind; callers pass {@code "partner"} for
     *                      partner-chain queries. Required.
     * @param aggregateId   the aggregate's natural key (e.g. partner code
     *                      {@code "GMEREMIT"}). Required.
     * @param page          zero-based page index (default 0).
     * @param size          page size (default {@value #DEFAULT_PAGE_SIZE}, max
     *                      {@value #MAX_PAGE_SIZE}).
     * @return a {@link PageView} of {@link AuditEntryView} entries, each carrying
     *         {@code chainValid} computed for the whole aggregate chain.
     */
    @GetMapping
    public PageView<AuditEntryView> list(
            @RequestParam String aggregateType,
            @RequestParam String aggregateId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size <= 0 ? DEFAULT_PAGE_SIZE : size), MAX_PAGE_SIZE);

        // Verify the entire chain BEFORE paginating — chain validity is a property of
        // the full aggregate, not just the current page. We verify even if the page
        // is empty so the caller always receives an explicit signal.
        boolean chainValid = auditLogService.verifyChain(aggregateType, aggregateId) < 0;

        Page<AuditLogEntity> dbPage = repository.findPageByAggregate(
                aggregateType, aggregateId, PageRequest.of(safePage, safeSize));

        List<AuditEntryView> entries = dbPage.getContent().stream()
                .map(e -> toView(e, chainValid))
                .toList();

        return new PageView<>(entries, safePage, safeSize, dbPage.getTotalElements());
    }

    /**
     * Map one persistence row to the public DTO, stripping raw hashes and
     * attaching the page-level {@code chainValid} flag.
     */
    private static AuditEntryView toView(AuditLogEntity e, boolean chainValid) {
        return new AuditEntryView(
                e.getRecordedAt(),
                e.getActorId(),
                e.getEventType(),
                bytesToString(e.getBeforeJsonb()),
                bytesToString(e.getAfterJsonb()),
                chainValid);
    }

    /**
     * Convert raw JSON bytes to a UTF-8 String for the wire DTO. Returns
     * {@code null} when the snapshot is {@code null} (e.g. first-write events
     * where there is no prior state).
     */
    private static String bytesToString(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
