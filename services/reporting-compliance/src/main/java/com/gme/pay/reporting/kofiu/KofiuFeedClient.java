package com.gme.pay.reporting.kofiu;

import com.gme.pay.reporting.channel.FilingTransmissionResult;

import java.nio.file.Path;

/**
 * Port (secondary / outbound): attempts to transmit a KoFIU feed file to the KoFIU
 * electronic reporting channel.
 *
 * <p>No real KoFIU submission channel exists in this environment — the wired
 * implementation everywhere today is {@link StubKofiuFeedClient}. Replace with a real
 * SFTP or API implementation once KoFIU credentials and the submission endpoint are
 * confirmed (OI-03).
 *
 * <h2>Honesty contract (GAP T5-2)</h2>
 * This port used to return a plain {@code String} receipt id, which forced the
 * no-channel implementation to invent one ({@code "STUB-<uuid>"}) and left callers unable
 * to distinguish a fabricated acknowledgement from a real one. It now returns
 * {@link FilingTransmissionResult}: an implementation that did not transmit returns
 * {@link FilingTransmissionResult#notTransmitted(String)} and <b>cannot</b> attach a
 * receipt id (the record's invariants reject it).
 */
public interface KofiuFeedClient {

    /**
     * Attempts to transmit the feed file at {@code feedFile} to KoFIU.
     *
     * @param feedFile  path to the local feed file to submit; must exist
     * @param batch     the batch the file was built from (metadata for logging)
     * @return the transmission outcome — a KoFIU-issued receipt id on success, otherwise
     *         the reason nothing was transmitted. Never a fabricated receipt.
     */
    FilingTransmissionResult submit(Path feedFile, KofiuReportBatch batch);
}
