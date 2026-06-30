package com.gme.pay.reporting.bok;

import com.gme.pay.reporting.domain.BokFxMapper;
import com.gme.pay.reporting.domain.BokFxRecord;
import com.gme.pay.reporting.domain.CommittedTransaction;
import com.gme.pay.reporting.domain.TransactionDirection;
import com.gme.pay.reporting.service.BokRecordPersistenceService;
import com.gme.pay.reporting.service.CommittedFxTransactionPort;
import com.gme.pay.reporting.service.TransactionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Daily scheduler that pulls the previous day's transactions from
 * {@link TransactionClient}, maps them via {@link BokFxMapper}, and writes
 * BOK FX1014/FX1015 report files via {@link BokFxFileBuilder}.
 *
 * <p>The scheduler runs daily at 02:00 KST (Asia/Seoul) — well after midnight
 * so all KST-day transactions are committed.  It reports on {@code today - 1 day}
 * in KST.
 *
 * <p>The scheduler is <b>gated</b> by {@code gmepay.reporting.bok.enabled}
 * (default {@code false}).  Set it to {@code true} in production via environment
 * variable or config-registry overlay.  Tests and development boots remain clean.
 *
 * <p><b>Spring 6 note:</b> {@code @Autowired} is declared on the constructor to be
 * explicit about Spring's injection point (defensive style, safe for future additions).
 *
 * <p>{@link com.gme.pay.reporting.ReportingComplianceApplication} carries
 * {@code @EnableScheduling} so this and other scheduled @Components activate.
 */
@Component
public class BokReportScheduler {

    private static final Logger log = LoggerFactory.getLogger(BokReportScheduler.class);

    /** KST timezone — all BOK report dates and schedule times use this zone. */
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final TransactionClient transactionClient;
    private final BokFxFileBuilder fileBuilder;
    private final BokFxMapper mapper;
    private final boolean enabled;

    /**
     * Rate-locked committed-FX source (carries {@code offerRateColl}, FX1015 #14).
     * Preferred over {@link #transactionClient} for file/record content when present;
     * may be null in unit tests that exercise only the file path.
     */
    @Nullable
    private final CommittedFxTransactionPort fxPort;

    /** Owned-datastore persistence of filings + bok_report_record rows; null in unit tests. */
    @Nullable
    private final BokRecordPersistenceService persistenceService;

    /**
     * Spring constructor. {@code @Autowired} is declared explicitly because this
     * {@code @Component} has more than one constructor (Spring 6 requires it).
     *
     * <p>The {@link CommittedFxTransactionPort} and {@link BokRecordPersistenceService}
     * carry the rate-locked FX fields and owned-datastore persistence respectively.
     */
    @Autowired
    public BokReportScheduler(
            TransactionClient transactionClient,
            BokFxFileBuilder fileBuilder,
            CommittedFxTransactionPort fxPort,
            BokRecordPersistenceService persistenceService,
            @Value("${gmepay.reporting.bok.enabled:false}") boolean enabled) {
        this.transactionClient = transactionClient;
        this.fileBuilder = fileBuilder;
        this.fxPort = fxPort;
        this.persistenceService = persistenceService;
        this.mapper = new BokFxMapper();
        this.enabled = enabled;
    }

    /**
     * Lightweight constructor for unit tests that exercise only file generation
     * (no FX port, no persistence). {@code @Value} metadata is not enforced at
     * instantiation, so {@code enabled} is passed directly.
     */
    public BokReportScheduler(
            TransactionClient transactionClient,
            BokFxFileBuilder fileBuilder,
            boolean enabled) {
        this(transactionClient, fileBuilder, null, null, enabled);
    }

    /**
     * Runs daily at 02:00 KST. Processes the previous KST calendar day.
     *
     * <p>Cron expression: {@code "0 0 2 * * ?"} with {@code zone="Asia/Seoul"}.
     */
    @Scheduled(cron = "0 0 2 * * ?", zone = "Asia/Seoul")
    public void runDailyBokReport() {
        LocalDate reportDate = LocalDate.now(KST).minusDays(1);
        runForDate(reportDate);
    }

    /**
     * Runs the BOK FX report pipeline for a specific report date.
     * Exposed as {@code public} so tests can trigger it directly without a Spring context.
     *
     * @param reportDate the KST calendar date to report on
     */
    public void runForDate(LocalDate reportDate) {
        if (!enabled) {
            log.debug("BOK reporting disabled (gmepay.reporting.bok.enabled=false). "
                    + "Skipping report for {}", reportDate);
            return;
        }

        log.info("BOK FX daily report starting for reportDate={}", reportDate);

        try {
            // Prefer the rate-locked committed-FX source (carries offerRateColl, FX1015 #14)
            // when wired; fall back to the canonical GET client otherwise.
            List<CommittedTransaction> transactions = (fxPort != null)
                    ? fxPort.fetchCommittedFx(reportDate, reportDate, null)
                    : transactionClient.fetchCommitted(reportDate, reportDate, null);

            // Persist filings + bok_report_record rows (idempotent) when persistence is wired.
            if (persistenceService != null) {
                persistenceService.persistForDate(transactions, reportDate);
            }

            // Map cross-border transactions; skip domestic/same-currency
            List<BokFxRecord> records = new ArrayList<>();
            for (CommittedTransaction txn : transactions) {
                if (txn.isSameCcyShortcircuit()
                        || txn.getDirection() == TransactionDirection.DOMESTIC) {
                    continue;
                }
                try {
                    records.add(mapper.toRecord(txn));
                } catch (IllegalArgumentException e) {
                    log.warn("Skipping txnId={} during BOK mapping: {}",
                            txn.getTxnId(), e.getMessage());
                }
            }

            BokFxFileBuilder.BokFileResult result = fileBuilder.buildFiles(records, reportDate);

            log.info("BOK FX daily report complete for {}: "
                    + "FX1014={} records → {}, FX1015={} records → {}",
                    reportDate,
                    result.getFx1014Count(), result.getFx1014Path(),
                    result.getFx1015Count(), result.getFx1015Path());

        } catch (IOException e) {
            log.error("BOK FX report file write failed for reportDate={}: {}",
                    reportDate, e.getMessage(), e);
        } catch (Exception e) {
            log.error("BOK FX report pipeline failed for reportDate={}: {}",
                    reportDate, e.getMessage(), e);
        }
    }
}
