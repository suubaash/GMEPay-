package com.gme.pay.reporting.kofiu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Scheduled driver for the daily KoFIU CTR/STR feed submission.
 *
 * <h2>Schedule</h2>
 * <p>Runs daily at 02:00 KST (Asia/Seoul = UTC+9, therefore 17:00 UTC previous
 * day). The cron expression uses Spring's timezone support:
 * {@code "0 0 2 * * *"} with {@code zone = "Asia/Seoul"}.
 *
 * <h2>Feature gate</h2>
 * <p>Gated by {@code gmepay.reporting.kofiu.enabled} (default {@code false}).
 * When disabled the scheduled method is a no-op. This avoids burning
 * transaction-source quota or writing files during integration-test runs.
 *
 * <h2>Spring 6 note</h2>
 * <p>This component has two constructors (the injected one and a package-private
 * test constructor). {@code @Autowired} is placed on the {@code @Value} constructor
 * so Spring selects it unambiguously — required when a {@code @Component} has
 * 2+ constructors.
 *
 * <h2>Configuration keys</h2>
 * <ul>
 *   <li>{@code gmepay.reporting.kofiu.enabled} — master on/off switch (default false)</li>
 *   <li>{@code gmepay.reporting.kofiu.output-dir} — feed file output dir (default /tmp/kofiu-feeds)</li>
 *   <li>{@code gmepay.reporting.kofiu.entity-id} — fallback KoFIU entity id</li>
 * </ul>
 */
@Component
public class KofiuFeedScheduler {

    private static final Logger log = LoggerFactory.getLogger(KofiuFeedScheduler.class);

    /** KST for deriving "yesterday" at run time. */
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final KofiuReportService reportService;
    private final KofiuFeedFileBuilder fileBuilder;
    private final KofiuFeedClient feedClient;
    private final boolean enabled;

    @Autowired
    public KofiuFeedScheduler(
            KofiuReportService reportService,
            KofiuFeedFileBuilder fileBuilder,
            KofiuFeedClient feedClient,
            @Value("${gmepay.reporting.kofiu.enabled:false}") boolean enabled) {
        this.reportService = reportService;
        this.fileBuilder = fileBuilder;
        this.feedClient = feedClient;
        this.enabled = enabled;
    }

    /**
     * Runs daily at 02:00 KST. Reports on the previous KST calendar day.
     *
     * <p>When {@code gmepay.reporting.kofiu.enabled} is {@code false} the method
     * logs and returns immediately without touching any downstream systems.
     */
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Seoul")
    public void runDaily() {
        if (!enabled) {
            log.debug("KoFIU feed scheduler disabled (gmepay.reporting.kofiu.enabled=false)");
            return;
        }

        LocalDate yesterday = LocalDate.now(KST).minusDays(1);
        log.info("KoFIU daily feed starting for reportDate={}", yesterday);

        try {
            KofiuReportBatch batch = reportService.buildDailyBatch(yesterday);
            if (batch.isEmpty()) {
                log.info("KoFIU daily feed: no reports for {}; skipping file write + submit",
                        yesterday);
                return;
            }

            Path feedFile = fileBuilder.buildAndWrite(batch);
            String receiptId = feedClient.submit(feedFile, batch);

            log.info("KoFIU daily feed completed: reportDate={}, ctr={}, str={}, "
                            + "file={}, receiptId={}",
                    yesterday,
                    batch.getCtrReports().size(),
                    batch.getStrReports().size(),
                    feedFile,
                    receiptId);

        } catch (Exception e) {
            log.error("KoFIU daily feed FAILED for reportDate={}: {}", yesterday, e.getMessage(), e);
            // Do not rethrow — scheduler must not be killed by a single-run failure.
        }
    }

    /**
     * Package-private — allows tests to drive a specific report date without
     * relying on real scheduling.
     */
    void runForDate(LocalDate reportDate) {
        if (!enabled) {
            log.debug("KoFIU feed scheduler disabled; skipping runForDate({})", reportDate);
            return;
        }
        KofiuReportBatch batch = reportService.buildDailyBatch(reportDate);
        if (!batch.isEmpty()) {
            Path feedFile = fileBuilder.buildAndWrite(batch);
            feedClient.submit(feedFile, batch);
        }
    }
}
