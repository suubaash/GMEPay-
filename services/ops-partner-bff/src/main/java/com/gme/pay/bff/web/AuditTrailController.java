package com.gme.pay.bff.web;

import com.gme.pay.bff.client.AuditTrailClient;
import com.gme.pay.contracts.AuditEntryView;
import com.gme.pay.contracts.PageView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pass-through endpoint for the per-partner audit hash-chain trail (agent 2C.1).
 *
 * <h2>URL</h2>
 *
 * <pre>
 * GET /v1/admin/audit-trail?aggregateType=partner&amp;aggregateId={code}&amp;page=0&amp;size=20
 * </pre>
 *
 * <p>This is a thin facade over {@link AuditTrailClient#list}: it applies
 * safe-page / safe-size bounds and then delegates to the client which calls
 * config-registry's {@code GET /v1/audit} endpoint. The Admin UI's
 * regulator-view page calls this to render the per-partner audit trail with
 * the {@code chainValid} integrity signal.
 *
 * <h2>Why a separate path from {@code /v1/admin/audit}?</h2>
 *
 * <p>{@code GET /v1/admin/audit} already exists and serves the operator
 * action log (authentication events, config changes) via
 * {@link AuditController}. The two feeds are conceptually different:
 * <ul>
 *   <li>{@code /v1/admin/audit} — operator-action log, no aggregate filter,
 *       served from the stub {@link com.gme.pay.bff.client.stub.StubAuditClient}.</li>
 *   <li>{@code /v1/admin/audit-trail} — per-partner hash-chain trail, served
 *       from config-registry via {@link AuditTrailClient}.</li>
 * </ul>
 *
 * <p>The path name {@code audit-trail} (rather than {@code audit/{code}}) is
 * intentional: passing the aggregate filter as a query param mirrors
 * config-registry's own endpoint shape and allows future aggregateTypes
 * (contacts, change_request, etc.) without a URL migration.
 */
@RestController
@RequestMapping("/v1/admin/audit-trail")
public class AuditTrailController {

    /** Default page size when the caller omits {@code size}. */
    static final int DEFAULT_PAGE_SIZE = 20;

    /** Hard cap on page size to bound the in-memory slice. */
    static final int MAX_PAGE_SIZE = 200;

    private final AuditTrailClient auditTrail;

    public AuditTrailController(AuditTrailClient auditTrail) {
        this.auditTrail = auditTrail;
    }

    /**
     * Retrieve the audit trail for a specific aggregate, newest first.
     *
     * @param aggregateType the aggregate kind (e.g. {@code "partner"}).
     * @param aggregateId   the aggregate's natural key (e.g. partner code).
     * @param page          zero-based page index (default 0).
     * @param size          page size (default 20, max 200).
     * @return paginated {@link AuditEntryView} entries with chain-validity signal.
     */
    @GetMapping
    public PageView<AuditEntryView> list(
            @RequestParam String aggregateType,
            @RequestParam String aggregateId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size <= 0 ? DEFAULT_PAGE_SIZE : size), MAX_PAGE_SIZE);

        return auditTrail.list(aggregateType, aggregateId, safePage, safeSize);
    }
}
