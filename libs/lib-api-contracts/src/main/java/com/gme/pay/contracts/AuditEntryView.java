package com.gme.pay.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Read DTO for a single audit log row returned by the regulator-facing audit read
 * endpoint ({@code GET /v1/audit}) in config-registry and passed through by the
 * BFF's {@code GET /v1/admin/audit-trail}.
 *
 * <h2>What is NOT included</h2>
 *
 * <p>Raw hash bytes ({@code prev_hash}, {@code row_hash}) are intentionally
 * omitted from this view. The regulator UI does not need them, and surfacing
 * them would invite callers to build fragile assertions against implementation
 * details of the hash chain. The chain integrity signal is conveyed instead via
 * the {@code chainValid} flag at the page level — one boolean per page, computed
 * by {@code AuditLogService.verifyChain} over the full aggregate chain.
 *
 * <h2>Before/after JSON</h2>
 *
 * <p>The raw bytes stored in {@code before_jsonb} / {@code after_jsonb} are
 * decoded to {@link String} at the service boundary. Any row where the snapshot
 * was {@code null} (e.g. a first-write event with no prior state) carries
 * {@code null} here; the UI renders that as "—" or "n/a".
 *
 * @param recordedAt  when this audit row was written (MICROS-truncated Instant).
 * @param actorId     who performed the action (operator email or {@code "system"}).
 * @param eventType   the event verb (e.g. {@code PARTNER_SAVED}).
 * @param beforeJson  the pre-change snapshot as a UTF-8 JSON string, or {@code null}
 *                    for first-write events.
 * @param afterJson   the post-change snapshot as a UTF-8 JSON string.
 * @param chainValid  {@code true} when the full aggregate chain verified intact up to
 *                    and including this page's newest row. Computed once per page by
 *                    {@code AuditLogService.verifyChain}; {@code false} signals a
 *                    potential tamper anywhere in the chain for this aggregate.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AuditEntryView(
        Instant recordedAt,
        String actorId,
        String eventType,
        String beforeJson,
        String afterJson,
        boolean chainValid) {
}
