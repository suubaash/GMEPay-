package com.gme.pay.settlement.transmission;

import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * The only writer of a settlement batch's {@link SettlementTransmissionState}.
 *
 * <h2>What it does today (GAP T4-5)</h2>
 * <ol>
 *   <li>{@link #stampReachableState(SettlementBatchEntity)} — called on every freshly GENERATED
 *       batch, so the row itself records that the file has not been sent and <b>why</b>, instead of
 *       leaving a reader to infer it from a null timestamp. With no channel configured (every
 *       environment today) that stamp is
 *       {@link SettlementTransmissionState#NOT_TRANSMITTED_CHANNEL_UNAVAILABLE} plus the registry's
 *       reason.</li>
 *   <li>{@link #recordTransmitted} / {@link #recordTransmissionFailure} — the seam a real SFTP leg
 *       plugs into. Both <b>refuse</b> while no channel is configured. This is not a placeholder that
 *       pretends to work: it is the guard that makes a fabricated transmission impossible rather than
 *       merely discouraged, exactly as {@code ReportFilingService} guards {@code TRANSMITTED} in
 *       reporting-compliance (T5-2).</li>
 * </ol>
 *
 * <p>No production code path calls {@link #recordTransmitted} — deliberately, because nothing
 * transmits. Real ZeroPay/KFTC SFTP needs scheme credentials and a certification run (external
 * gate); the platform's only {@code SftpTransport} bean writes to a local temp directory.
 */
@Component
public class SettlementTransmissionRecorder {

    private static final Logger log = LoggerFactory.getLogger(SettlementTransmissionRecorder.class);

    private final SettlementTransmissionChannelRegistry channels;
    private final SettlementBatchRepository batchRepository;

    public SettlementTransmissionRecorder(SettlementTransmissionChannelRegistry channels,
                                          SettlementBatchRepository batchRepository) {
        this.channels = channels;
        this.batchRepository = batchRepository;
    }

    /**
     * Stamp the batch with the most advanced transmission state this deployment can reach, and the
     * reason when that is short of TRANSMITTED. Idempotent and never overwrites a real transmission:
     * a batch already {@link SettlementTransmissionState#TRANSMITTED} is left alone.
     *
     * <p>Does <b>not</b> save — the caller is inside the generation transaction and saves the batch
     * itself, so the stamp commits atomically with the batch it describes.
     */
    public void stampReachableState(SettlementBatchEntity batch) {
        if (batch == null || batch.getTransmissionState().isSent()) {
            return;
        }
        SettlementTransmissionState reachable = channels.reachableState();
        if (reachable.isSent()) {
            // A channel exists but nothing has been attempted for this batch yet.
            batch.markNotTransmitted(SettlementTransmissionState.NOT_TRANSMITTED,
                    "A transmission channel is configured (" + channels.status().channelId()
                            + ") but this batch has not been transmitted yet.");
            return;
        }
        batch.markNotTransmitted(reachable, channels.unavailableReason());
    }

    /**
     * Record that the batch's file was genuinely handed to the scheme.
     *
     * @throws TransmissionChannelUnavailableException when no real channel is configured — i.e.
     *         always, today. Refusing is the point: with no channel there is nothing that could have
     *         transmitted, so accepting the claim would be fabricating one.
     */
    public SettlementBatchEntity recordTransmitted(SettlementBatchEntity batch, Instant at) {
        requireLiveChannel(batch, "transmitted");
        batch.markTransmitted(at == null ? Instant.now() : at, channels.status().channelId());
        log.info("settlement batch {} TRANSMITTED over channel {}",
                batch.getBatchId(), channels.status().channelId());
        return batchRepository.save(batch);
    }

    /**
     * Record that a transfer over a live channel was attempted and failed.
     *
     * @throws TransmissionChannelUnavailableException when no channel is configured — a deployment
     *         with no channel has not <em>failed</em> to transmit, it has not tried, and conflating
     *         the two would hide the standing gap behind a transient-looking error.
     */
    public SettlementBatchEntity recordTransmissionFailure(SettlementBatchEntity batch, String detail) {
        requireLiveChannel(batch, "failed to transmit");
        batch.markNotTransmitted(SettlementTransmissionState.TRANSMISSION_FAILED, detail);
        log.warn("settlement batch {} TRANSMISSION_FAILED over channel {}: {}",
                batch.getBatchId(), channels.status().channelId(), detail);
        return batchRepository.save(batch);
    }

    private void requireLiveChannel(SettlementBatchEntity batch, String claim) {
        if (batch == null) {
            throw new IllegalArgumentException("batch is required");
        }
        if (!channels.isLive()) {
            throw new TransmissionChannelUnavailableException(
                    "refusing to record settlement batch " + batch.getBatchId() + " as " + claim
                            + ": " + channels.unavailableReason());
        }
    }
}
