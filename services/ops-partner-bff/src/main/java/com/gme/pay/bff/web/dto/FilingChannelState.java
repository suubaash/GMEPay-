package com.gme.pay.bff.web.dto;

/**
 * Whether one regulatory lane has a live transmission channel — the BFF projection of
 * {@code reporting-compliance}'s {@code filing_channels[]} entry (GAP T5-2).
 *
 * <p>Carried so the Reports / Compliance surfaces can distinguish "we generated a report" from
 * "the authority received it". Every lane is {@code channelLive=false} today (BOK SFTP endpoint
 * OI-03, NTS mTLS certificate OI-02, KoFIU endpoint + file spec are all externally gated), and
 * {@code reason} names the missing configuration.
 *
 * @param lane            {@code BOK | KOFIU | HOMETAX}
 * @param channelLive     {@code true} only when a real transmission channel is configured
 * @param reachableStatus the most advanced filing status this lane can currently reach —
 *                        {@code NOT_FILED_CHANNEL_UNAVAILABLE} while {@code channelLive} is false
 * @param reason          why the channel is unavailable; non-null whenever {@code channelLive} is false
 */
public record FilingChannelState(
        String lane,
        boolean channelLive,
        String reachableStatus,
        String reason) {}
