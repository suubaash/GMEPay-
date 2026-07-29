package com.gme.pay.reporting.channel;

import java.util.Objects;

/**
 * Outcome of handing a generated report to a regulatory transmission channel.
 *
 * <p>Replaces the previous "return a receipt id String" contract on the channel ports.
 * That contract had no way to express <i>"nothing was transmitted"</i>, so the stub
 * implementations invented receipt ids ({@code "STUB-<uuid>"}) and callers could not tell
 * a fabricated acknowledgement from a real one. This type makes the two cases distinct
 * and makes the failure case the cheap one to express: a channel that cannot transmit
 * returns {@link #notTransmitted(String)} with a reason and <b>no receipt id at all</b>.
 *
 * @param transmitted {@code true} only when the bytes were accepted by a real channel
 * @param receiptId   the authority-issued receipt / acknowledgement id; always
 *                    {@code null} when {@code transmitted} is false
 * @param reason      why nothing was transmitted; always {@code null} when
 *                    {@code transmitted} is true
 */
public record FilingTransmissionResult(boolean transmitted, String receiptId, String reason) {

    public FilingTransmissionResult {
        if (transmitted) {
            Objects.requireNonNull(receiptId, "a transmitted filing must carry the authority receipt id");
            if (receiptId.isBlank()) {
                throw new IllegalArgumentException("a transmitted filing must carry a non-blank receipt id");
            }
            if (reason != null) {
                throw new IllegalArgumentException("a transmitted filing must not carry an unavailability reason");
            }
        } else {
            if (receiptId != null) {
                throw new IllegalArgumentException(
                        "a filing that was not transmitted must not carry a receipt id — "
                                + "receipt ids may only come from the authority");
            }
            Objects.requireNonNull(reason, "a filing that was not transmitted must explain why");
        }
    }

    /** The authority accepted the bytes and issued {@code receiptId}. */
    public static FilingTransmissionResult transmitted(String receiptId) {
        return new FilingTransmissionResult(true, receiptId, null);
    }

    /** Nothing was transmitted — {@code reason} explains what is missing. */
    public static FilingTransmissionResult notTransmitted(String reason) {
        return new FilingTransmissionResult(false, null, reason);
    }
}
