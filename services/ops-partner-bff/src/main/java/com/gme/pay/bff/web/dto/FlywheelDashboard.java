package com.gme.pay.bff.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The 7-metric growth-loop dashboard from {@code docs/QR_HUB_GROWTH_FLYWHEEL.md} §5,
 * served by {@code GET /v1/admin/flywheel}. Composed from config-registry (network
 * sides), transaction-mgmt (TPV + activation), prefunding (capital), revenue-ledger
 * (take rate) and the platform-settings store (ops-entered metrics the platform
 * cannot yet derive: acceptance points, adapter time-to-live, monthly active payers).
 *
 * <p>Every metric is nullable: {@code null} means "not yet measurable" (no data in
 * the window, or the ops-entered setting is unset) — the UI renders a dash plus a
 * hint, never a fake zero. Money stays {@link BigDecimal} end-to-end per
 * {@code docs/MONEY_CONVENTION.md}.
 */
public record FlywheelDashboard(
        Window window,
        Network network,
        Volume volume,
        Capital capital,
        LoopHealth loopHealth) {

    /** The resolved reporting window (defaults to the trailing 30 days). */
    public record Window(Instant from, Instant to) {}

    /**
     * Metric 1+2 — the two network sides. {@code acceptancePoints} is the summed
     * merchant count across live schemes, ops-entered via platform setting
     * {@code flywheel.acceptance_points} until scheme merchant feeds carry it.
     */
    public record Network(
            int liveWallets,
            int totalWallets,
            int liveSchemes,
            int totalSchemes,
            BigDecimal acceptancePoints) {}

    /**
     * Metric 3 — cross-border TPV and blended take rate over the window.
     * {@code tpvUsd} sums {@code prefundingDeductedUsd} across APPROVED
     * transactions; {@code tpvTruncated} is true when the scan hit the page cap
     * so the sum covers only the first {@code scannedTxnCount} of
     * {@code approvedTxnCount} rows (never silently — flywheel doc "no silent caps").
     */
    public record Volume(
            BigDecimal tpvUsd,
            boolean tpvTruncated,
            long scannedTxnCount,
            long approvedTxnCount,
            BigDecimal revenueUsd,
            BigDecimal takeRatePct) {}

    /** Metric 4 — prefunding turn ratio (loop C health): window TPV / current prefund. */
    public record Capital(
            BigDecimal totalPrefundUsd,
            BigDecimal prefundingTurnRatio) {}

    /**
     * Metrics 5-7 — loop-health rates. {@code medianPartnerTimeToFirstTxnHours}
     * (loop B) is computed from onboardedAt → first APPROVED transaction across
     * activated partners; {@code adapterTimeToLiveDays} (loop A) and
     * {@code monthlyActivePayers} (loops D/E) are ops-entered platform settings
     * ({@code flywheel.adapter_time_to_live_days}, {@code flywheel.monthly_active_payers});
     * {@code txnsPerPayer} divides the window's approved count by active payers.
     */
    public record LoopHealth(
            Long medianPartnerTimeToFirstTxnHours,
            int activatedPartnerCount,
            int pendingPartnerCount,
            BigDecimal adapterTimeToLiveDays,
            BigDecimal monthlyActivePayers,
            BigDecimal txnsPerPayer) {}
}
