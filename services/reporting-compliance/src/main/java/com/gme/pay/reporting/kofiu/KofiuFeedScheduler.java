package com.gme.pay.reporting.kofiu;

import com.gme.pay.reporting.channel.FilingTransmissionResult;
import com.gme.pay.reporting.persistence.ReportFiling;
import com.gme.pay.reporting.persistence.ReportFilingService;
import com.gme.pay.reporting.validation.FilingArtifactValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
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

    /** Owned-datastore persistence of CTR/STR filings; null when running without a DB. */
    @Nullable
    private final ReportFilingService filingService;

    /** Local artifact format validation; null in unit tests that skip validation. */
    @Nullable
    private final FilingArtifactValidator validator;

    @Autowired
    public KofiuFeedScheduler(
            KofiuReportService reportService,
            KofiuFeedFileBuilder fileBuilder,
            KofiuFeedClient feedClient,
            ReportFilingService filingService,
            FilingArtifactValidator validator,
            @Value("${gmepay.reporting.kofiu.enabled:false}") boolean enabled) {
        this.reportService = reportService;
        this.fileBuilder = fileBuilder;
        this.feedClient = feedClient;
        this.filingService = filingService;
        this.validator = validator;
        this.enabled = enabled;
    }

    /**
     * Package-private constructor for tests predating the artifact validator.
     * Validation is skipped (filings stop at GENERATED) — never a more advanced state.
     */
    KofiuFeedScheduler(
            KofiuReportService reportService,
            KofiuFeedFileBuilder fileBuilder,
            KofiuFeedClient feedClient,
            ReportFilingService filingService,
            boolean enabled) {
        this(reportService, fileBuilder, feedClient, filingService, null, enabled);
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
            FilingTransmissionResult result = feedClient.submit(feedFile, batch);
            persistFilings(yesterday, batch.getCtrReports().size(),
                    batch.getStrReports().size(), feedFile, result);

            if (result.transmitted()) {
                log.info("KoFIU daily feed TRANSMITTED: reportDate={}, ctr={}, str={}, "
                                + "file={}, receiptId={}",
                        yesterday,
                        batch.getCtrReports().size(),
                        batch.getStrReports().size(),
                        feedFile,
                        result.receiptId());
            } else {
                // Normal path today: the feed was generated but no KoFIU channel exists.
                log.warn("KoFIU daily feed GENERATED BUT NOT FILED: reportDate={}, ctr={}, "
                                + "str={}, file={} — {}",
                        yesterday,
                        batch.getCtrReports().size(),
                        batch.getStrReports().size(),
                        feedFile,
                        result.reason());
            }

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
            FilingTransmissionResult result = feedClient.submit(feedFile, batch);
            persistFilings(reportDate, batch.getCtrReports().size(),
                    batch.getStrReports().size(), feedFile, result);
        }
    }

    /**
     * Records idempotent CTR/STR {@code report_filing} rows for the date (one per type),
     * advancing each through the honest lifecycle:
     * GENERATED → (VALIDATED if the artifact passes local checks) →
     * TRANSMITTED only when the channel really transmitted, otherwise
     * NOT_FILED_CHANNEL_UNAVAILABLE with the reason.
     *
     * <p>Best-effort: a persistence failure must not abort the feed run.
     */
    private void persistFilings(LocalDate reportDate, int ctrCount, int strCount,
                                Path feedFile, FilingTransmissionResult result) {
        if (filingService == null) {
            return;
        }
        try {
            FilingArtifactValidator.Outcome outcome =
                    (validator != null) ? validator.validate(feedFile) : null;
            if (outcome != null && !outcome.valid()) {
                log.warn("KoFIU feed artifact failed local validation for {}: {}",
                        reportDate, outcome.reason());
            }
            for (String reportType : new String[] {"CTR", "STR"}) {
                int count = "CTR".equals(reportType) ? ctrCount : strCount;
                ReportFiling filing = filingService.openFiling(
                        ReportFiling.Lane.KOFIU, reportType, reportDate);
                filingService.recordGenerated(filing.getId(), count, feedFile.toString());
                if (outcome != null && outcome.valid()) {
                    filingService.recordValidated(filing.getId());
                }
                // Truth comes from the channel outcome, never from the caller's optimism.
                filingService.recordTransmissionResult(filing.getId(), result);
            }
        } catch (Exception e) {
            log.error("KoFIU filing persistence failed for reportDate={}: {}",
                    reportDate, e.getMessage(), e);
        }
    }
}
