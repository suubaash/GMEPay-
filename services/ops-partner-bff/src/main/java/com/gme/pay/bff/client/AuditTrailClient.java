package com.gme.pay.bff.client;

import com.gme.pay.contracts.AuditEntryView;
import com.gme.pay.contracts.PageView;

/**
 * Client for reading the partner audit trail from config-registry's
 * {@code GET /v1/audit} endpoint (agent 2C.1).
 *
 * <p>This is deliberately separate from the old {@link AuditClient} which
 * covers the operator-action audit (authentication events, config changes)
 * served by {@code GET /v1/admin/audit}. The new endpoint covers the
 * per-partner audit hash-chain trail needed by the regulator UI.
 *
 * <p>The stub implementation returns an empty page so the BFF starts cleanly
 * in standalone / unit-test mode. The REST implementation delegates to
 * config-registry over HTTP.
 */
public interface AuditTrailClient {

    /**
     * Returns a paginated page of audit entries for the given aggregate,
     * ordered newest-first. Each entry carries {@code chainValid} reflecting
     * the integrity of the full aggregate chain at query time.
     *
     * @param aggregateType the aggregate kind (e.g. {@code "partner"}).
     * @param aggregateId   the aggregate's natural key (e.g. {@code "GMEREMIT"}).
     * @param page          zero-based page index.
     * @param size          maximum number of entries per page.
     * @return a page of {@link AuditEntryView} records.
     */
    PageView<AuditEntryView> list(String aggregateType, String aggregateId, int page, int size);
}
