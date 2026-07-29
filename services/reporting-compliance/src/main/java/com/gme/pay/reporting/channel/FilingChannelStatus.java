package com.gme.pay.reporting.channel;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gme.pay.reporting.persistence.ReportFiling;

/**
 * Whether a regulatory lane has a <b>live transmission channel</b> — i.e. whether this
 * service is actually able to hand a generated report to the authority.
 *
 * <p>This type exists because "we generated and validated a report" and "the authority
 * received it" are different facts, and only the first one is true today for every lane
 * (GAP T5-2). It is serialised into {@code GET /v1/reports} and
 * {@code GET /v1/reports/filing-channels} so an operator or auditor can see at a glance
 * that nothing has been filed, and <i>why</i>.
 *
 * @param lane            the regulatory lane (BOK / KOFIU / HOMETAX)
 * @param live            {@code true} only when a real transmission channel is configured
 * @param reachableStatus the most advanced {@link ReportFiling.Status} a filing on this
 *                        lane can currently reach — {@code NOT_FILED_CHANNEL_UNAVAILABLE}
 *                        while {@code live} is false
 * @param reason          human-readable explanation; non-null whenever {@code live} is
 *                        false, naming the missing configuration
 */
public record FilingChannelStatus(
        @JsonProperty("lane") ReportFiling.Lane lane,
        @JsonProperty("channel_live") boolean live,
        @JsonProperty("reachable_status") ReportFiling.Status reachableStatus,
        @JsonProperty("reason") String reason) {

    /** A lane with a configured, live transmission channel. */
    public static FilingChannelStatus live(ReportFiling.Lane lane) {
        return new FilingChannelStatus(lane, true, ReportFiling.Status.ACKNOWLEDGED, null);
    }

    /**
     * A lane with no transmission channel. Reports can still be generated and validated;
     * they simply cannot leave this JVM, and the filing terminates at
     * {@link ReportFiling.Status#NOT_FILED_CHANNEL_UNAVAILABLE}.
     */
    public static FilingChannelStatus unavailable(ReportFiling.Lane lane, String reason) {
        return new FilingChannelStatus(lane, false,
                ReportFiling.Status.NOT_FILED_CHANNEL_UNAVAILABLE, reason);
    }
}
