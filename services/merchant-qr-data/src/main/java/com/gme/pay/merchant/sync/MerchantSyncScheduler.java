package com.gme.pay.merchant.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Scheduled daily merchant/QR sync ingest (UC-07-01 / UC-07-02).
 *
 * <p>Scans the configurable inbound directory for ZeroPay batch files, delegates
 * processing to {@link MerchantSyncService}, and logs a summary of each run.
 *
 * <p><strong>Activation gate:</strong> this bean is only registered when
 * {@code gmepay.merchant-sync.enabled=true} (default: {@code false}).
 * Set the property in {@code application.yml} or via an environment variable
 * ({@code GMEPAY_MERCHANT_SYNC_ENABLED=true}) to activate.
 *
 * <p><strong>Schedule:</strong> runs daily at 02:00 KST (UTC+9), i.e. 17:00 UTC
 * the previous day. Override via {@code gmepay.merchant-sync.cron} if needed.
 * Default cron: {@code 0 0 17 * * *} (UTC).
 *
 * <p><strong>Inbound directory:</strong> configured via
 * {@code gmepay.merchant-sync.inbound-dir} (default: {@code ./data/zeropay-inbound}).
 * For local development and tests, drop fixture files (e.g. {@code ZP0041_20260615.dat})
 * into this directory — NO real ZeroPay SFTP credentials are required.
 *
 * <p><strong>Spring 6 multi-constructor note:</strong> This component has two
 * constructors (primary DI + {@code @Value} variant). The {@code @Value}-annotated
 * constructor carries {@code @Autowired} so Spring Boot's component scan selects
 * it correctly (Spring 6 requires explicit {@code @Autowired} when 2+ constructors
 * are present on a {@code @Component}).
 */
@Component
@ConditionalOnProperty(name = "gmepay.merchant-sync.enabled", havingValue = "true")
public class MerchantSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(MerchantSyncScheduler.class);

    /** File extensions accepted as ZeroPay inbound batch files. */
    private static final List<String> ACCEPTED_EXTENSIONS = List.of(".dat", ".txt", ".csv");

    private final MerchantSyncService syncService;
    private final String inboundDir;
    private final String cron;

    /**
     * Primary constructor used by Spring when {@code @Value} injection is needed.
     * {@code @Autowired} is required here because this class has multiple constructors
     * (Spring 6 / Boot 3.x does not infer a single candidate when 2+ ctors exist).
     */
    @Autowired
    public MerchantSyncScheduler(
            MerchantSyncService syncService,
            @Value("${gmepay.merchant-sync.inbound-dir:./data/zeropay-inbound}") String inboundDir,
            @Value("${gmepay.merchant-sync.cron:0 0 17 * * *}") String cron) {
        this.syncService = syncService;
        this.inboundDir = inboundDir;
        this.cron = cron;
    }

    /**
     * Runs the merchant/QR sync on the configured KST daily schedule.
     *
     * <p>Scans {@code gmepay.merchant-sync.inbound-dir} for files whose names
     * start with a recognised ZeroPay prefix (ZP0041/0043/0045/0047/0051/0053).
     * Each file is processed in alphabetical order (incremental deltas before
     * full lists when both are present). Results are logged; processing continues
     * on a per-file basis even when individual files fail.
     *
     * <p>Cron expression is fixed at declaration time; the {@code cron} field is
     * retained for observability/logging only. To change the schedule at runtime
     * use a {@code ScheduledTaskRegistrar} instead (future enhancement).
     */
    @Scheduled(cron = "${gmepay.merchant-sync.cron:0 0 17 * * *}",
               zone = "Asia/Seoul")
    public void runDailySync() {
        log.info("MerchantSyncScheduler: starting daily sync from dir={}, cron={}", inboundDir, cron);

        Path dir = Paths.get(inboundDir);
        if (!Files.isDirectory(dir)) {
            log.warn("MerchantSyncScheduler: inbound-dir does not exist or is not a directory: {}",
                    dir.toAbsolutePath());
            return;
        }

        List<Path> files = listInboundFiles(dir);
        if (files.isEmpty()) {
            log.info("MerchantSyncScheduler: no inbound files found in {}", dir.toAbsolutePath());
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

    /**
     * Lists all ZeroPay inbound files in the given directory in sorted (alphabetical) order.
     * Only files whose name starts with a recognised ZeroPay prefix are included.
     */
    List<Path> listInboundFiles(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> isAcceptedExtension(p.getFileName().toString()))
                    .filter(p -> ZeroPayFileType.fromFilename(p.getFileName().toString()) != null)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.error("MerchantSyncScheduler: failed to list inbound dir {}: {}",
                    dir.toAbsolutePath(), e.getMessage(), e);
            return List.of();
        }
    }

    private static boolean isAcceptedExtension(String filename) {
        String lower = filename.toLowerCase();
        return ACCEPTED_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    /** Returns the resolved absolute path of the configured inbound directory. */
    public Path getInboundDirPath() {
        return Paths.get(inboundDir).toAbsolutePath();
    }
}
