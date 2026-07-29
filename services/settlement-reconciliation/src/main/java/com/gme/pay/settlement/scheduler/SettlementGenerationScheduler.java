package com.gme.pay.settlement.scheduler;

import com.gme.pay.settlement.batch.SettlementBatchJobService;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.runlog.BatchRunExecutor;
import com.gme.pay.settlement.runlog.BatchRunRecorder;
import com.gme.pay.settlement.runlog.BatchRunTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Drives OUTBOUND settlement-file generation on the daily KST windows (the complement of
 * {@link ReconScheduler}, which handles the inbound result files):
 * <ul>
 *   <li>~05:00 KST — {@code ZP0061} morning settlement-request file</li>
 *   <li>~14:00 KST — {@code ZP0063} afternoon settlement-request file</li>
 *   <li>~22:00 KST — {@code ZP0065} payment-detail + {@code ZP0066} refund-detail files</li>
 * </ul>
 *
 * <p>Crons are expressed directly in KST via the {@code zone} attribute (clearer + DST-proof; KST has no
 * DST) and are overridable per environment. All three are gated on {@code gmepay.settlement.generation.enabled}
 * (default {@code false}) — set {@code true} in production only, so dev/test never auto-generate.
 *
 * <h2>T3-4: runs are recorded and failures are reported</h2>
 * <p>Every window now runs through {@link BatchRunExecutor} instead of a bare
 * {@code try { … } catch (Exception e) { log.error(…) }}. A single failure is still contained — it must not
 * crash the scheduler or skip the later windows — but it is no longer <em>silent</em>:
 * <ul>
 *   <li>a {@code batch_runs} row is committed in its own transaction, so it survives the rollback of the
 *       job's transaction (which previously erased all trace of a failed run);</li>
 *   <li>a {@code BATCH_RUN_FAILED} CRITICAL alert goes out on the existing {@code gmepay.ops.alert}
 *       pipeline, and whether that notification succeeded is stamped back on the same row;</li>
 *   <li>the configured business-day calendar is consulted, and a run on a date it does not cover raises
 *       {@code BATCH_CALENDAR_UNVERIFIED} rather than quietly assuming the day is a KRW banking day;</li>
 *   <li>successful and gate-disabled runs are recorded too, so "did last night's 05:00 happen?" is
 *       answerable — including the case where somebody deployed with the gate still off.</li>
 * </ul>
 * Re-running a window an operator needs to retry is {@code POST /v1/settlements/batch/rerun}
 * ({@link com.gme.pay.settlement.rerun.BatchRerunController}), which goes through the same executor.
 *
 * <p>The detail window runs AFTER both request windows by clock ordering, so the ZP0061/ZP0063 batches (and
 * their settlement_lines, which the detail files are built from) already exist; if they do not,
 * {@link SettlementBatchJobService#runDetailWindow} degrades to an empty file and logs.
 *
 * <p><b>Multi-instance:</b> generation is idempotent (createOrGet + the {@code (file_type, business_date,
 * window)} unique key + the PENDING-only guard), so a duplicate fire is a safe no-op. A distributed lock
 * (ShedLock) to avoid the redundant work + the insert race is a documented follow-up for multi-instance
 * deployments; the current single-instance deployment does not need it.
 *
 * <p>{@code @EnableScheduling} is already declared on {@link ReconScheduler}, so the scheduling infrastructure
 * is active service-wide; this component only registers additional {@code @Scheduled} methods.
 */
@Component
public class SettlementGenerationScheduler {

    private static final Logger log = LoggerFactory.getLogger(SettlementGenerationScheduler.class);
    private static final String KST = "Asia/Seoul";
    private static final ZoneId KST_ZONE = ZoneId.of(KST);

    private final boolean enabled;
    private final SettlementBatchJobService job;
    private final BatchRunExecutor runs;

    public SettlementGenerationScheduler(
            @Value("${gmepay.settlement.generation.enabled:false}") boolean enabled,
            SettlementBatchJobService job,
            BatchRunExecutor runs) {
        this.enabled = enabled;
        this.job = job;
        this.runs = runs;
    }

    /** ZP0061 morning request file — ~05:00 KST. */
    @Scheduled(cron = "${gmepay.settlement.generation.morning-cron:0 0 5 * * *}", zone = KST)
    public void generateMorningRequest() {
        runWindowSafely("ZP0061", "MORNING");
    }

    /** ZP0063 afternoon request file — ~14:00 KST. */
    @Scheduled(cron = "${gmepay.settlement.generation.afternoon-cron:0 0 14 * * *}", zone = KST)
    public void generateAfternoonRequest() {
        runWindowSafely("ZP0063", "AFTERNOON");
    }

    /** ZP0065 payment-detail + ZP0066 refund-detail files — ~22:00 KST (after both request windows). */
    @Scheduled(cron = "${gmepay.settlement.generation.detail-cron:0 0 22 * * *}", zone = KST)
    public void generateDetailFiles() {
        if (disabled("ZP0065", "DETAIL") | disabled("ZP0066", "DETAIL")) {
            return;   // non-short-circuiting so BOTH disabled runs are recorded, not just the first
        }
        runDetailSafely("ZP0065");
        runDetailSafely("ZP0066");
    }

    private void runWindowSafely(String fileType, String window) {
        if (disabled(fileType, window)) {
            return;
        }
        LocalDate date = LocalDate.now(KST_ZONE);
        runs.execute(BatchRunRecorder.KIND_GENERATION, fileType, window, date, BatchRunTrigger.SCHEDULER,
                () -> job.runWindow(fileType, window),
                SettlementGenerationScheduler::describe);
    }

    private void runDetailSafely(String fileType) {
        LocalDate date = LocalDate.now(KST_ZONE);
        runs.execute(BatchRunRecorder.KIND_GENERATION, fileType, "DETAIL", date, BatchRunTrigger.SCHEDULER,
                () -> job.runDetailWindow(fileType),
                SettlementGenerationScheduler::describe);
    }

    /** Ledger summary for a generated batch. */
    static BatchRunExecutor.RunSummary describe(SettlementBatchEntity batch) {
        return new BatchRunExecutor.RunSummary(
                batch.getBatchId(), batch.getStatus(), batch.getRecordCount());
    }

    /**
     * Feature-gate check. A disabled window is RECORDED (outcome {@code SKIPPED_DISABLED}) rather than only
     * debug-logged: a production deployment that forgot {@code gmepay.settlement.generation.enabled=true}
     * generates no settlement files at all, which is precisely the silent-batch-failure class T3-4 is
     * about, and the run ledger is the only place an operator would notice.
     */
    private boolean disabled(String fileType, String window) {
        if (!enabled) {
            log.debug("SettlementGenerationScheduler: {}/{} skipped "
                    + "(gmepay.settlement.generation.enabled=false)", fileType, window);
            runs.recordDisabled(BatchRunRecorder.KIND_GENERATION, fileType, window,
                    LocalDate.now(KST_ZONE), "gmepay.settlement.generation.enabled=false");
            return true;
        }
        return false;
    }
}
