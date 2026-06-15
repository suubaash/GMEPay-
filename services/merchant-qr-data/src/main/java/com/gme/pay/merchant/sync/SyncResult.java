package com.gme.pay.merchant.sync;

import java.time.Instant;
import java.util.List;

/**
 * Immutable summary of a single merchant-sync file processing run.
 *
 * <p>Returned by {@link MerchantSyncService} and logged by
 * {@link MerchantSyncScheduler}.
 *
 * @param filename    Source file that was processed
 * @param fileType    Detected ZeroPay file type
 * @param upserted    Number of merchant/QR records upserted (inserted or updated)
 * @param deactivated Number of records deactivated (QD rows or MD rows)
 * @param skipped     Number of rows skipped (blank lines, comments, headers)
 * @param errors      Number of rows that failed to parse or persist
 * @param errorDetails First N error messages for logging
 * @param processedAt Timestamp when processing completed
 * @param success     {@code true} if the file completed without a fatal error
 */
public record SyncResult(
        String filename,
        ZeroPayFileType fileType,
        int upserted,
        int deactivated,
        int skipped,
        int errors,
        List<String> errorDetails,
        Instant processedAt,
        boolean success) {

    /** Convenience factory for a fatal (unparseable file) failure. */
    public static SyncResult fatal(String filename, ZeroPayFileType fileType, String reason) {
        return new SyncResult(filename, fileType, 0, 0, 0, 1,
                List.of(reason), Instant.now(), false);
    }

    @Override
    public String toString() {
        return String.format(
                "SyncResult{file=%s, type=%s, upserted=%d, deactivated=%d, skipped=%d, errors=%d, success=%s, at=%s}",
                filename, fileType, upserted, deactivated, skipped, errors, success, processedAt);
    }
}
