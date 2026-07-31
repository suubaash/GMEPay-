package com.gme.pay.bff.compliance;

import java.util.Locale;
import java.util.Set;

/**
 * Maps a regulatory filing status coming from {@code reporting-compliance} onto the status this
 * BFF is willing to state, and refuses to invent a success.
 *
 * <h2>Why this exists (GAP T5-2)</h2>
 * <p>{@code RestReportingClient} used to pass the literal {@code "GENERATED"} for every run,
 * regardless of what the service said. Now that {@code GET /v1/reports} carries an honest
 * {@code filing_status} (+ {@code filing_channel_unavailable_reason}), hardcoding anything would
 * re-fabricate a rosier status one layer up — exactly the leak the service-side fix closed.
 *
 * <p>Three rules:
 * <ol>
 *   <li><b>Honest values pass through unchanged</b> — including
 *       {@link #NOT_FILED_CHANNEL_UNAVAILABLE}, the terminal state every lane actually reaches
 *       today.</li>
 *   <li><b>Absent/unrecognised → {@link #UNKNOWN}</b>, never a success. An older service version
 *       that does not send {@code filing_status} means we do not know; it does not mean filed,
 *       and it does not mean {@code GENERATED} either (that is a real capability claim: aggregated
 *       + artifact produced).</li>
 *   <li><b>The retired fabricated vocabulary is reclassified, not echoed</b> —
 *       {@code SUBMITTED}/{@code CONFIRMED}/{@code ACCEPTED}/{@code FILED} could only ever have
 *       been written by the stub clients that have since been removed, and Flyway {@code V003} in
 *       {@code reporting-compliance} reclassified the historical rows the same way. Echoing them
 *       would show an operator a filing that never left the JVM.</li>
 * </ol>
 */
public final class FilingStatuses {

    /** We have no filing status from upstream. Deliberately not a success and not {@code GENERATED}. */
    public static final String UNKNOWN = "UNKNOWN";

    /** Generated locally, never transmitted — the terminal state of all three lanes today. */
    public static final String NOT_FILED_CHANNEL_UNAVAILABLE = "NOT_FILED_CHANNEL_UNAVAILABLE";

    /** The current honest vocabulary ({@code ReportFiling.Status} in reporting-compliance). */
    private static final Set<String> HONEST = Set.of(
            "PENDING", "GENERATED", "VALIDATED", NOT_FILED_CHANNEL_UNAVAILABLE,
            "TRANSMITTED", "ACKNOWLEDGED", "FAILED");

    /** Retired vocabulary that asserted an acceptance nothing ever produced. */
    private static final Set<String> RETIRED_SUCCESS = Set.of(
            "SUBMITTED", "CONFIRMED", "ACCEPTED", "FILED");

    private FilingStatuses() {}

    /** Normalise an upstream {@code filing_status} into a status this BFF can honestly state. */
    public static String fromUpstream(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (RETIRED_SUCCESS.contains(u)) {
            return NOT_FILED_CHANNEL_UNAVAILABLE;
        }
        return HONEST.contains(u) ? u : UNKNOWN;
    }

    /**
     * The reason to surface alongside {@link #fromUpstream(String)}. The upstream reason wins;
     * otherwise we explain why we could not take the raw value at face value, so an operator
     * reading the row is never left guessing why it says {@code UNKNOWN}.
     *
     * @param raw            the upstream {@code filing_status} verbatim (may be null)
     * @param upstreamReason the upstream {@code filing_channel_unavailable_reason} (may be null)
     * @return a human-readable reason, or {@code null} when the status speaks for itself
     */
    public static String reasonFor(String raw, String upstreamReason) {
        if (upstreamReason != null && !upstreamReason.isBlank()) {
            return upstreamReason;
        }
        if (raw == null || raw.isBlank()) {
            return "reporting-compliance did not report a filing_status for this run "
                    + "(service predates the T5-2 filing-honesty change); "
                    + "nothing may be assumed filed";
        }
        String u = raw.trim().toUpperCase(Locale.ROOT);
        if (RETIRED_SUCCESS.contains(u)) {
            return "reporting-compliance reported the retired status '" + raw.trim()
                    + "', which no real filing channel could have produced; "
                    + "reclassified to " + NOT_FILED_CHANNEL_UNAVAILABLE;
        }
        if (!HONEST.contains(u)) {
            return "reporting-compliance reported an unrecognised filing_status '" + raw.trim()
                    + "'; treated as " + UNKNOWN;
        }
        return null;
    }
}
