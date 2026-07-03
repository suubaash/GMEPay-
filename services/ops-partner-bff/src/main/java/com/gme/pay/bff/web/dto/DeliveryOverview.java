package com.gme.pay.bff.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * Dashboard-shaped delivery overview for the Admin UI — "is the platform actually delivering
 * payments?". Orchestrates transaction-mgmt's delivery stats (success rate, decline reasons) with
 * the config-registry partner list to compute per-partner activation latency.
 *
 * <p>Wire shape:
 * <pre>
 * {
 *   "window": { "from", "to" },
 *   "successRate": {
 *       "overall":   { "total", "approved", "declined", "successRatePct" },
 *       "byPartner":  [ { "partner", "total", "approved", "declined", "successRatePct" } ],
 *       "byCorridor": [ { "corridor", "total", "approved", "declined", "successRatePct" } ]
 *   },
 *   "declineReasons": [ { "reason", "count" } ],
 *   "activation": [ { "partner", "onboardedAt", "firstApprovedAt", "activationHours", "status" } ]
 * }
 * </pre>
 */
public record DeliveryOverview(
        Window window,
        SuccessRate successRate,
        List<DeclineReason> declineReasons,
        List<Activation> activation
) {

    public record Window(Instant from, Instant to) {}

    public record SuccessRate(
            Slice overall,
            List<PartnerSlice> byPartner,
            List<CorridorSlice> byCorridor) {}

    /** The platform-wide success slice. */
    public record Slice(long total, long approved, long declined, double successRatePct) {}

    public record PartnerSlice(String partner, long total, long approved, long declined,
                               double successRatePct) {}

    public record CorridorSlice(String corridor, long total, long approved, long declined,
                                double successRatePct) {}

    public record DeclineReason(String reason, long count) {}

    /**
     * One partner's activation record.
     *
     * @param partner         partner code / ref
     * @param onboardedAt     the partner's created/onboarded timestamp (bitemporal validFrom)
     * @param firstApprovedAt earliest approved transaction for the partner; null if never activated
     * @param activationHours hours between onboarding and first approval; null if not activated
     * @param status          {@code "activated"} when firstApprovedAt present, else {@code "pending"}
     */
    public record Activation(
            String partner,
            Instant onboardedAt,
            Instant firstApprovedAt,
            Long activationHours,
            String status) {}
}
