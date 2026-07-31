package com.gme.pay.settlement.transmission;

/**
 * Whether a generated outbound settlement file <b>actually left this platform</b>.
 *
 * <h2>Why this is a separate axis from {@code SettlementBatchStatus} (GAP T4-5)</h2>
 * The batch lifecycle answers "how far has this batch got through recon?". It used to be asked
 * to answer a second, unrelated question — "did we send it?" — by way of a {@code TRANSMITTED}
 * state that the reconciliation path walked through as pure bookkeeping. Nothing had transmitted
 * anything: the only {@code SftpTransport} bean is {@code LocalDirSftpTransport}, which writes to
 * a local temp directory, and real ZeroPay/KFTC SFTP needs scheme credentials plus a
 * certification run (an external gate, not a coding task).
 *
 * <p>So transmission now lives on its own column with its own vocabulary. A batch can be
 * {@code RECONCILED} — because a confirmation file genuinely arrived and genuinely tied out —
 * while its transmission state still says, on the record, that GME never sent the request file.
 * Those two facts are both true and are no longer collapsed into one word.
 *
 * <p>{@link #TRANSMITTED} is reachable only through
 * {@link SettlementTransmissionRecorder#recordTransmitted}, which refuses unless
 * {@link SettlementTransmissionChannelRegistry} shows a real configured channel. There is no
 * other writer, in production or in test fixtures, that can put a batch into it.
 */
public enum SettlementTransmissionState {

    /**
     * The bytes have not left. Either the batch has not been generated yet, or a channel exists
     * and the transfer has not been attempted. This is the initial state of every batch row.
     */
    NOT_TRANSMITTED,

    /**
     * <b>The state of every batch in every environment today.</b> The file was generated and can
     * be inspected, but this deployment has no settlement transmission channel at all, so the
     * file will never be sent by it. Deliberately a distinct value from {@link #NOT_TRANSMITTED}:
     * "not sent yet" and "cannot be sent by this deployment" are different operational facts, and
     * only the second one is a standing gap rather than a pending task.
     */
    NOT_TRANSMITTED_CHANNEL_UNAVAILABLE,

    /**
     * A transfer was attempted over a live channel and failed. Reachable only via
     * {@link SettlementTransmissionRecorder#recordTransmissionFailure}; like
     * {@link #TRANSMITTED} it requires a configured channel, because a deployment with no channel
     * has not failed to transmit — it has not tried.
     */
    TRANSMISSION_FAILED,

    /**
     * The file was handed to the scheme over a real, configured channel. Not reachable today.
     */
    TRANSMITTED;

    /** {@code true} only for {@link #TRANSMITTED} — i.e. the file demonstrably left. */
    public boolean isSent() {
        return this == TRANSMITTED;
    }
}
