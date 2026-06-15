package com.gme.pay.reporting.kofiu;

import java.nio.file.Path;

/**
 * Port (secondary / outbound): submits a KoFIU feed file to the KoFIU
 * electronic reporting channel and returns a receipt identifier.
 *
 * <p>No real KoFIU submission channel exists in this environment — the default
 * implementation is {@link StubKofiuFeedClient} which returns a fake receipt id.
 * Replace with a real SFTP or API implementation once KoFIU credentials and
 * the submission endpoint are confirmed (OI-03).
 */
public interface KofiuFeedClient {

    /**
     * Submits the feed file at {@code feedFile} to KoFIU.
     *
     * @param feedFile  path to the local feed file to submit; must exist
     * @param batch     the batch the file was built from (metadata for logging)
     * @return a receipt / acknowledgement id (fake when using the stub)
     */
    String submit(Path feedFile, KofiuReportBatch batch);
}
