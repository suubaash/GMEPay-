package com.gme.pay.merchant.sync;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Port abstracting the <em>delivery transport</em> for inbound ZeroPay merchant/QR
 * batch files, decoupling {@link MerchantSyncScheduler} from where the files
 * physically come from.
 *
 * <p>Real production delivery is via the KFTC/ZeroPay SFTP drop (PGP-encrypted),
 * which is <strong>externally blocked</strong> (no credentials / no IDD sign-off in
 * this environment). This seam lets the scheduler run today over a local directory
 * ({@link LocalDirectoryFileSource}) and have a real {@code SftpMerchantFileSource}
 * dropped in later without touching the scheduler or sync service. See INTEGRATION
 * REQUEST in the build report.
 *
 * <p>Implementations must be side-effect free on {@link #listAvailableFiles()} (no
 * mutation of the source until {@link #markProcessed(Path)} is called), so a failed
 * run can be retried on the next schedule.
 */
public interface MerchantFileSource {

    /**
     * Lists the ZeroPay batch files currently available for ingest, in deterministic
     * processing order (incremental deltas before full lists when both are present).
     * Only files whose name carries a recognised ZeroPay prefix are returned.
     *
     * @return ordered list of local file paths ready to hand to {@link MerchantSyncService};
     *         empty when nothing is available
     * @throws IOException if the underlying transport cannot be enumerated
     */
    List<Path> listAvailableFiles() throws IOException;

    /**
     * Acknowledges that a file returned by {@link #listAvailableFiles()} has been
     * successfully processed, so it is not re-ingested on the next run (e.g. archived
     * or removed). Implementations should be tolerant of being called for an
     * already-acknowledged file (idempotent).
     *
     * @param file the file previously returned by {@link #listAvailableFiles()}
     * @throws IOException if the acknowledgement (archive/move/delete) fails
     */
    void markProcessed(Path file) throws IOException;

    /** Human-readable description of the configured source for log/observability output. */
    String describe();
}
