package com.gme.pay.txn.api.dto;

import java.time.Instant;

/**
 * Response for {@code GET /v1/transactions/payer-stats} — the payer-level activity
 * feed for the flywheel dashboard (loops D/E, docs/QR_HUB_GROWTH_FLYWHEEL.md §5).
 *
 * @param window       the half-open instant window covered: {@code [from, to)}
 * @param activePayers distinct non-null {@code user_ref} values among APPROVED
 *                     transactions in the window. 0 means "no payer-tagged approved
 *                     transactions" — consumers treat that as unmeasured, never as a
 *                     real zero-payer month.
 */
public record PayerStatsResponse(Window window, long activePayers) {

    /** The half-open instant window the stats cover: {@code [from, to)}. */
    public record Window(Instant from, Instant to) {}
}
