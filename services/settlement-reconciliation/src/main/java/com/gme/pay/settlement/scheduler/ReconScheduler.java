package com.gme.pay.settlement.scheduler;

import com.gme.pay.settlement.parser.ZP0062Parser;
import com.gme.pay.settlement.parser.ZP0064Parser;
import com.gme.pay.settlement.parser.ZeroPayResultRecord;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.persistence.SettlementBatchRepository;
import com.gme.pay.settlement.recon.ReconDiffEngine;
import com.gme.pay.settlement.runlog.BatchRunExecutor;
import com.gme.pay.settlement.runlog.BatchRunRecorder;
import com.gme.pay.settlement.runlog.BatchRunTrigger;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Recon scheduler — triggers reconciliation in KST windows.
 *
 * <p>Per spec:
 * <ul>
 *   <li>~10:00 KST — process ZP0062 (morning settlement result)</li>
 *   <li>~19:00 KST — process ZP0064 (afternoon settlement result)</li>
 * </ul>
 *
 * <p>Both schedules are gated on {@code gmepay.settlement.recon.enabled} (default {@code false}).
 * Set to {@code true} in production environments only — not in test or dev.
 *
 * <p>In Phase 2a the scheduler reads files from the configured inbox directory
 * ({@code gmepay.settlement.recon.inbox-dir}). Phase 2b will replace the file-system
 * trigger with an SFTP pull from ZeroPay (out of scope here per spec).
 *
 * <h2>T3-4: runs are recorded and failures are reported</h2>
 * <p>Both windows run through {@link BatchRunExecutor} rather than a bare log-only catch, so an inbound
 * recon that throws leaves a durable {@code batch_runs} row (own transaction) and raises a
 * {@code BATCH_RUN_FAILED} CRITICAL alert on the existing {@code gmepay.ops.alert} pipeline, and a run on a
 * date the configured business-day calendar does not cover raises {@code BATCH_CALENDAR_UNVERIFIED}.
 *
 * <p><b>A missing inbox file is treated as a failure, not a shrug.</b> It used to be a {@code log.warn} and
 * an early return — indistinguishable from a clean day. ZeroPay owes us a result file for every settlement
 * request it received, so its absence on a business day is precisely the "silent settlement discrepancy
 * discovered by the counterparty" this gap describes. On a configured non-business date the window is
 * skipped before it ever looks for the file, so a holiday no longer produces a false alarm — which is what
 * makes turning this into an alert affordable at all.
 *
 * <p>Spring 6 note: {@link Component} with 2 constructors would need {@code @Autowired}
 * on the Spring-injected constructor. This class has exactly one constructor so the
 * annotation is omitted (Spring injects automatically).
 */
@EnableScheduling
@Component
public class ReconScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconScheduler.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final boolean enabled;
    private final ReconFileSource fileSource;
    private final ZP0062Parser zp0062Parser;
    private final ZP0064Parser zp0064Parser;
    private final ReconDiffEngine diffEngine;
    private final SettlementBatchRepository batchRepository;
    private final BatchRunExecutor runs;

    public ReconScheduler(
            @Value("${gmepay.settlement.recon.enabled:false}") boolean enabled,
            ReconFileSource fileSource,
            ZP0062Parser zp0062Parser,
            ZP0064Parser zp0064Parser,
            ReconDiffEngine diffEngine,
            SettlementBatchRepository batchRepository,
            BatchRunExecutor runs) {
        this.enabled = enabled;
        this.fileSource = fileSource;
        this.zp0062Parser = zp0062Parser;
        this.zp0064Parser = zp0064Parser;
        this.diffEngine = diffEngine;
        this.batchRepository = batchRepository;
        this.runs = runs;
    }

    /**
     * Morning recon — runs at 10:05 KST (01:05 UTC) every day.
     * Processes ZP0062 morning settlement result files found in the inbox directory.
     *
     * <p>Cron is expressed in UTC because Spring's {@code @Scheduled} uses the JVM default
     * timezone unless overridden. The UTC offset accounts for KST = UTC+9.
     */
    // T3-11: one recon run per window across the whole cluster. Two replicas would each parse the
    // same ZP0062 file and each write its own recon exceptions and batch state, so the exception
    // count an operator reconciles against would be doubled. lockAtMostFor bounds a crashed holder,
    // and is set well above a file parse because the cost of expiring EARLY is a concurrent second
    // run -- exactly what the lock exists to prevent.
    @Scheduled(cron = "${gmepay.settlement.recon.morning-cron:5 5 1 * * *}")
    @SchedulerLock(name = "ReconScheduler_morningRecon",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT0S")
    public void morningRecon() {
        LocalDate today = LocalDate.now(KST);
        if (disabled("ZP0062", "MORNING", today)) {
            return;
        }
        log.info("ReconScheduler: starting morning recon for date={}", today);
        runs.execute(BatchRunRecorder.KIND_RECON, "ZP0062", "MORNING", today, BatchRunTrigger.SCHEDULER,
                () -> {
                    processZP0062(today);
                    return Boolean.TRUE;
                }, null);
    }

    /**
     * Afternoon recon — runs at 19:05 KST (10:05 UTC) every day.
     * Processes ZP0064 afternoon settlement result files.
     */
    @Scheduled(cron = "${gmepay.settlement.recon.afternoon-cron:5 5 10 * * *}")
    @SchedulerLock(name = "ReconScheduler_afternoonRecon",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT0S")
    public void afternoonRecon() {
        LocalDate today = LocalDate.now(KST);
        if (disabled("ZP0064", "AFTERNOON", today)) {
            return;
        }
        log.info("ReconScheduler: starting afternoon recon for date={}", today);
        runs.execute(BatchRunRecorder.KIND_RECON, "ZP0064", "AFTERNOON", today, BatchRunTrigger.SCHEDULER,
                () -> {
                    processZP0064(today);
                    return Boolean.TRUE;
                }, null);
    }

    /**
     * Feature-gate check, recorded rather than only debug-logged (see
     * {@link SettlementGenerationScheduler} for why a silently-disabled batch is itself a T3-4 failure mode).
     */
    private boolean disabled(String fileType, String window, LocalDate date) {
        if (!enabled) {
            log.debug("ReconScheduler: {}/{} skipped (gmepay.settlement.recon.enabled=false)",
                    fileType, window);
            runs.recordDisabled(BatchRunRecorder.KIND_RECON, fileType, window, date,
                    "gmepay.settlement.recon.enabled=false");
            return true;
        }
        return false;
    }

    /**
     * Process a ZP0062 file for the given settlement date.
     *
     * <p>Reads the file from {@code <inboxDir>/ZP0062_<YYYYMMDD>.txt}, parses it,
     * and runs the diff engine.
     *
     * <p><b>T3-4:</b> a missing file now throws {@link ReconInputMissingException} and a parse/diff failure
     * propagates, instead of both being swallowed into a log line. The caller
     * ({@link BatchRunExecutor}) is what contains the failure — it records the run, alerts, and lets the
     * next window proceed — so the containment is preserved while the silence is not.
     *
     * <p>Phase 2b TODO: replace file-system read with SFTP pull from ZeroPay.
     */
    void processZP0062(LocalDate date) {
        String filename = "ZP0062_" + date.format(DATE_FMT) + ".txt";
        List<String> lines = fileSource.readInboxFile(filename);
        if (lines == null) {
            throw new ReconInputMissingException(filename);
        }
        List<ZeroPayResultRecord> records = zp0062Parser.parse(lines);
        reconcileAgainstRequestBatch("ZP0061", "MORNING", date, records, "ZP0062");
    }

    /**
     * Process a ZP0064 file for the given settlement date. Same failure contract as
     * {@link #processZP0062(LocalDate)}.
     */
    void processZP0064(LocalDate date) {
        String filename = "ZP0064_" + date.format(DATE_FMT) + ".txt";
        List<String> lines = fileSource.readInboxFile(filename);
        if (lines == null) {
            throw new ReconInputMissingException(filename);
        }
        List<ZeroPayResultRecord> records = zp0064Parser.parse(lines);
        reconcileAgainstRequestBatch("ZP0063", "AFTERNOON", date, records, "ZP0064");
    }

    /**
     * Reconcile a parsed result file against the persisted outbound request batch it confirms (ZP0062
     * ↔ ZP0061 morning, ZP0064 ↔ ZP0063 afternoon). When the request batch exists, the authoritative
     * batch-tied recon runs (net per merchant from the persisted lines, idempotent, advances batch
     * lifecycle). When it does not exist — e.g. a result file arrived before the request batch was
     * generated — fall back to the legacy live-txn diff so the scheme records are still inspected and
     * any MISSING_INTERNAL is surfaced.
     */
    private void reconcileAgainstRequestBatch(String requestFileType, String window, LocalDate date,
                                              List<ZeroPayResultRecord> records, String resultFileType) {
        SettlementBatchEntity batch = batchRepository
                .findByFileTypeAndBusinessDateAndSettlementWindow(requestFileType, date, window)
                .orElse(null);
        if (batch == null) {
            log.warn("ReconScheduler: no {} request batch for {} — {} processed via fallback live-txn diff",
                    requestFileType, date, resultFileType);
            diffEngine.runDiff(resultFileType + "-" + date.format(DATE_FMT), date, records);
            return;
        }
        diffEngine.runDiffForBatch(batch, records);
    }
}
