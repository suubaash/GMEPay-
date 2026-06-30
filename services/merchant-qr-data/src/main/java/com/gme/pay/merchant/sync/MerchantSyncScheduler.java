package com.gme.pay.merchant.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Scheduled daily merchant/QR sync ingest (UC-07-01 / UC-07-02).
 *
 * <p>Pulls available ZeroPay batch files from a {@link MerchantFileSource} (transport
 * port), delegates processing to {@link MerchantSyncService}, acknowledges each
 * successfully-processed file back to the source, and logs a per-run summary.
 *
 * <p><strong>Transport abstraction:</strong> the scheduler no longer scans the
 * filesystem directly — it depends on the {@link MerchantFileSource} port. The default
 * {@link LocalDirectoryFileSource} reads a local directory (no SFTP credentials needed);
 * a real SFTP-backed source can be dropped in without changing this class.
 *
 * <p><strong>Activation gate:</strong> this bean is only registered when
 * {@code gmepay.merchant-sync.enabled=true} (default: {@code false}).
 * Set the property in {@code application.yml} or via an environment variable
 * ({@code GMEPAY_MERCHANT_SYNC_ENABLED=true}) to activate.
 *
 * <p><strong>Schedule:</strong> runs daily at 02:00 KST (UTC+9). Override via
 * {@code gmepay.merchant-sync.cron} if needed. Default cron: {@code 0 0 2 * * *}
 * with {@code zone=Asia/Seoul}.
 */
@Component
@ConditionalOnProperty(name = "gmepay.merchant-sync.enabled", havingValue = "true")
public class MerchantSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(MerchantSyncScheduler.class);

    private final MerchantSyncService syncService;
    private final MerchantFileSource fileSource;

    public MerchantSyncScheduler(MerchantSyncService syncService, MerchantFileSource fileSource) {
        this.syncService = syncService;
        this.fileSource = fileSource;
    }

    /**
     * Runs the merchant/QR sync on the configured KST daily schedule.
     *
     * <p>Lists available files via the {@link MerchantFileSource} (incremental deltas
     * before full lists when both are present), processes each, and acknowledges
     * successfully-processed files. Processing continues on a per-file basis even
     * when individual files fail.
     */
    @Scheduled(cron = "${gmepay.merchant-sync.cron:0 0 2 * * *}",
               zone = "Asia/Seoul")
    public void runDailySync() {
        log.info("MerchantSyncScheduler: starting daily sync from source={}", fileSource.describe());

        List<Path> files;
        try {
            files = fileSource.listAvailableFiles();
        } catch (IOException e) {
            log.error("MerchantSyncScheduler: failed to list files from {}: {}",
                    fileSource.describe(), e.getMessage(), e);
            return;
        }

        if (files.isEmpty()) {
            log.info("MerchantSyncScheduler: no inbound files found in {}", fileSource.describe());
            return;
        }

        log.info("MerchantSyncScheduler: found {} file(s) to process", files.size());

        int totalUpserted = 0;
        int totalDeactivated = 0;
        int totalErrors = 0;
        int filesProcessed = 0;
        int filesFailed = 0;

        for (Path file : files) {
            try {
                SyncResult result = syncService.processFile(file);
                totalUpserted += result.upserted();
                totalDeactivated += result.deactivated();
                totalErrors += result.errors();
                if (result.success()) {
                    filesProcessed++;
                    acknowledge(file);
                } else {
                    filesFailed++;
                }
                if (!result.errorDetails().isEmpty()) {
                    result.errorDetails().forEach(e ->
                            log.warn("  [{}] row error: {}", result.filename(), e));
                }
            } catch (Exception e) {
                filesFailed++;
                log.error("MerchantSyncScheduler: unexpected error processing file {}: {}",
                        file.getFileName(), e.getMessage(), e);
            }
        }

        log.info("MerchantSyncScheduler: daily sync complete — "
                        + "files_ok={}, files_failed={}, upserted={}, deactivated={}, row_errors={}",
                filesProcessed, filesFailed, totalUpserted, totalDeactivated, totalErrors);
    }

    private void acknowledge(Path file) {
        try {
            fileSource.markProcessed(file);
        } catch (IOException e) {
            log.warn("MerchantSyncScheduler: failed to acknowledge processed file {}: {}",
                    file.getFileName(), e.getMessage());
        }
    }
}
