package com.gme.pay.bff.settlement;

import java.util.Locale;
import java.util.Set;

/**
 * Maps settlement statuses coming from {@code settlement-reconciliation} onto what this BFF is
 * willing to state, and refuses to invent a success.
 *
 * <h2>Why this exists (GAP T4-5)</h2>
 * {@code RestSettlementClient} used to hardcode {@code status="COMPLETED"} on every row, with a
 * javadoc that called the recomputed figure "final". Two separate problems:
 * <ol>
 *   <li>{@code COMPLETED} is not in the upstream vocabulary at all — a batch is
 *       PENDING/GENERATED/TRANSMITTED/RECEIVED/RECONCILED/ERROR — so the word could only ever have
 *       been the BFF's own invention;</li>
 *   <li>it was applied to rows that corresponded to <b>no persisted batch</b>, because the only
 *       upstream read recomputed per-merchant figures from unbatched transactions.</li>
 * </ol>
 * Now that {@code GET /v1/settlements/batches} carries the real {@code status} and a separate,
 * honest {@code transmissionState}, hardcoding anything here would re-fabricate a rosier picture one
 * layer up — exactly the leak the service-side fix closed. Same discipline, same shape as
 * {@link com.gme.pay.bff.compliance.FilingStatuses} (T5-2).
 *
 * <h2>Three rules</h2>
 * <ol>
 *   <li>Honest values pass through unchanged.</li>
 *   <li>Absent/unrecognised → {@link #UNKNOWN}, never a success. An older service version that does
 *       not send a status means we do not know; it does not mean settled.</li>
 *   <li>The retired invented vocabulary is reclassified, not echoed — {@code COMPLETED} and friends
 *       could only have come from the BFF's own former hardcode, and echoing them would show an
 *       operator or a partner a settlement that may never have been reconciled, let alone sent.</li>
 * </ol>
 */
public final class SettlementStatuses {

    /** We have no status from upstream. Deliberately not a success. */
    public static final String UNKNOWN = "UNKNOWN";

    /** No settlement file has left the platform — the state of every batch today. */
    public static final String NOT_TRANSMITTED_CHANNEL_UNAVAILABLE = "NOT_TRANSMITTED_CHANNEL_UNAVAILABLE";

    /** The real lifecycle vocabulary ({@code SettlementBatchStatus} in settlement-reconciliation). */
    private static final Set<String> HONEST_LIFECYCLE = Set.of(
            "PENDING", "GENERATED", "TRANSMITTED", "RECEIVED", "RECONCILED", "ERROR");

    /**
     * Statuses no upstream service has ever produced. {@code COMPLETED} is the literal this BFF used
     * to hardcode; the others are the plausible neighbours a later change might reach for.
     */
    private static final Set<String> RETIRED_INVENTED = Set.of(
            "COMPLETED", "SETTLED", "PAID", "SUCCESS", "DONE", "FINAL");

    /** The real transmission vocabulary ({@code SettlementTransmissionState}). */
    private static final Set<String> HONEST_TRANSMISSION = Set.of(
            "NOT_TRANSMITTED", NOT_TRANSMITTED_CHANNEL_UNAVAILABLE, "TRANSMISSION_FAILED", "TRANSMITTED");

    private SettlementStatuses() {}

    /** Normalise an upstream batch lifecycle {@code status}. */
    public static String lifecycleFromUpstream(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (RETIRED_INVENTED.contains(u)) {
            return UNKNOWN;
        }
        return HONEST_LIFECYCLE.contains(u) ? u : UNKNOWN;
    }

    /**
     * Normalise an upstream {@code transmissionState}. An absent value becomes
     * {@link #UNKNOWN} — <b>not</b> {@code NOT_TRANSMITTED} — because "the service did not tell us"
     * and "the service told us it was not sent" are different, and only the second is a fact we may
     * report. Neither is ever {@code TRANSMITTED}: no absent or unrecognised value can mean sent.
     */
    public static String transmissionFromUpstream(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        return HONEST_TRANSMISSION.contains(u) ? u : UNKNOWN;
    }

    /** {@code true} only when upstream said, in its own vocabulary, that the file was sent. */
    public static boolean isTransmitted(String raw) {
        return "TRANSMITTED".equals(transmissionFromUpstream(raw));
    }

    /**
     * The reason to surface alongside a normalised status. The upstream reason wins; otherwise we
     * explain why we could not take the raw value at face value, so a reader is never left guessing
     * why a row says {@code UNKNOWN}.
     *
     * @param rawLifecycle    upstream {@code status} verbatim (may be null)
     * @param rawTransmission upstream {@code transmissionState} verbatim (may be null)
     * @param upstreamReason  upstream {@code transmissionDetail} (may be null)
     */
    public static String reasonFor(String rawLifecycle, String rawTransmission, String upstreamReason) {
        if (upstreamReason != null && !upstreamReason.isBlank()) {
            return upstreamReason;
        }
        if (rawTransmission == null || rawTransmission.isBlank()) {
            return "settlement-reconciliation did not report a transmissionState for this batch "
                    + "(service predates the T4-5 transmission-honesty change); "
                    + "nothing may be assumed transmitted";
        }
        String lifecycle = rawLifecycle == null ? "" : rawLifecycle.trim().toUpperCase(Locale.ROOT);
        if (RETIRED_INVENTED.contains(lifecycle)) {
            return "settlement-reconciliation reported the status '" + rawLifecycle.trim()
                    + "', which is not in its own lifecycle vocabulary and could only have been "
                    + "invented downstream; treated as " + UNKNOWN;
        }
        if (!lifecycle.isEmpty() && !HONEST_LIFECYCLE.contains(lifecycle)) {
            return "settlement-reconciliation reported an unrecognised status '" + rawLifecycle.trim()
                    + "'; treated as " + UNKNOWN;
        }
        if (!HONEST_TRANSMISSION.contains(rawTransmission.trim().toUpperCase(Locale.ROOT))) {
            return "settlement-reconciliation reported an unrecognised transmissionState '"
                    + rawTransmission.trim() + "'; treated as " + UNKNOWN
                    + " — an uninterpretable value never means transmitted";
        }
        return null;
    }
}
