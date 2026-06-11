package com.gme.pay.bff.client;

import com.gme.pay.bff.web.dto.Page;

import java.time.Instant;

/**
 * Read-only view of the operator audit log. Production implementation calls
 * the audit service (owned by {@code auth-identity} in Phase 4); Phase-1
 * default is an in-memory stub so the Admin UI Audit page can render without
 * booting the auth service.
 *
 * <p>Audit entries cover operator actions on configuration (partner create,
 * rounding-mode update, scheme enable/disable, etc.) and authentication
 * events (login.success / login.failure).
 */
public interface AuditClient {

    /**
     * Returns a page of audit entries ordered newest-first. {@code page} is
     * zero-indexed; {@code size} is the number of rows per page.
     */
    Page<AuditEntry> list(int page, int size);

    /**
     * One row in the audit log. {@code action} uses dotted-resource form
     * (e.g. {@code partner.create}, {@code rounding-mode.update}); {@code
     * target} is the entity id the action operated on (e.g. partner id);
     * {@code detail} is a free-text human-readable summary.
     */
    record AuditEntry(
            String id,
            String actor,
            String action,
            String target,
            Instant at,
            String detail
    ) {}
}
