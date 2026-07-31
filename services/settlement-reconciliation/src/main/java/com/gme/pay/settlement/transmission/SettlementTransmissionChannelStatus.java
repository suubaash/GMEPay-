package com.gme.pay.settlement.transmission;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Whether this service has a <b>live settlement transmission channel</b> — i.e. whether it is
 * able to hand a generated ZP0061/ZP0063/ZP0065/ZP0066 file to the scheme at all.
 *
 * <p>Mirrors {@code reporting-compliance}'s {@code FilingChannelStatus} (GAP T5-2) deliberately:
 * "we generated a settlement file" and "the scheme received it" are different facts, and only the
 * first is true today (GAP T4-5). Serialised into
 * {@code GET /v1/settlements/transmission-channel} and onto every batch/statement row, so an
 * operator, a partner or an auditor can see at a glance that nothing has been transmitted, and why.
 *
 * @param channelId       identifier of the configured channel, or {@code null} when there is none
 * @param live            {@code true} only when a real transmission channel is configured
 * @param reachableState  the most advanced {@link SettlementTransmissionState} a batch can
 *                        currently reach — {@code NOT_TRANSMITTED_CHANNEL_UNAVAILABLE} while
 *                        {@code live} is false
 * @param reason          human-readable explanation; non-null whenever {@code live} is false
 */
public record SettlementTransmissionChannelStatus(
        @JsonProperty("channel_id") String channelId,
        @JsonProperty("channel_live") boolean live,
        @JsonProperty("reachable_state") SettlementTransmissionState reachableState,
        @JsonProperty("reason") String reason) {

    /** A configured, live transmission channel. */
    public static SettlementTransmissionChannelStatus live(String channelId) {
        return new SettlementTransmissionChannelStatus(
                channelId, true, SettlementTransmissionState.TRANSMITTED, null);
    }

    /**
     * No transmission channel. Files are still generated, checksummed and reconciled against
     * whatever confirmation file arrives; they simply never leave this JVM, and every batch
     * terminates at {@link SettlementTransmissionState#NOT_TRANSMITTED_CHANNEL_UNAVAILABLE}.
     */
    public static SettlementTransmissionChannelStatus unavailable(String reason) {
        return new SettlementTransmissionChannelStatus(
                null, false, SettlementTransmissionState.NOT_TRANSMITTED_CHANNEL_UNAVAILABLE, reason);
    }
}
