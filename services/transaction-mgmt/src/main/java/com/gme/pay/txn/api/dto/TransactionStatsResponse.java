package com.gme.pay.txn.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Delivery-dashboard statistics for GET /v1/transactions/stats — product analytics answering
 * "is the platform actually delivering payments?".
 *
 * <p>Canonical wire shape:
 * <pre>
 * {
 *   "window":  { "from": ISO-8601, "to": ISO-8601 },
 *   "totals":  { "total", "approved", "declined", "successRatePct" },
 *   "byPartner":   [ { "partner", "total", "approved", "declined", "successRatePct" } ],
 *   "byCorridor":  [ { "corridor", "total", "approved", "declined", "successRatePct" } ],
 *   "declineReasons": [ { "reason", "count" } ]
 * }
 * </pre>
 *
 * <p><b>"approved"</b> = the terminal success status the state machine uses, {@code APPROVED}
 * (V006 CHECK constraint). <b>"declined"</b> = the terminal not-approved failure statuses
 * {@code FAILED, CANCELLED, REVERSED}. <b>corridor</b> = the {@code scheme_id} column (the QR
 * scheme / network); a null scheme collapses under {@code "UNKNOWN"}. <b>declineReasons</b> uses
 * the real {@code failure_reason} column (V004); a declined row with a null failure_reason is
 * labelled by its status (e.g. {@code "CANCELLED"}). {@code successRatePct} = round(approved /
 * total * 100, 1); 0 when total = 0.
 */
public record TransactionStatsResponse(
        Window window,
        Totals totals,
        List<PartnerStat> byPartner,
        List<CorridorStat> byCorridor,
        List<DeclineReason> declineReasons
) {

    /** The half-open instant window the stats cover: {@code [from, to)}. */
    public record Window(Instant from, Instant to) {}

    /** Platform-wide totals across the window. */
    public record Totals(long total, long approved, long declined, double successRatePct) {}

    /** Per-partner (partner_ref) delivery slice. */
    public record PartnerStat(String partner, long total, long approved, long declined,
                              double successRatePct) {}

    /** Per-corridor (scheme_id) delivery slice. */
    public record CorridorStat(String corridor, long total, long approved, long declined,
                               double successRatePct) {}

    /** One decline-reason tally. */
    public record DeclineReason(String reason, long count) {}
}
