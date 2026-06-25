package com.gme.pay.settlement.scheduler;

import com.gme.pay.settlement.batch.SettlementBatchJobService;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
 * (default {@code false}) — set {@code true} in production only, so dev/test never auto-generate. Each run is
 * wrapped so a single failure is logged and never escalates to crash the scheduler or skip later windows.
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

    private final boolean enabled;
    private final SettlementBatchJobService job;

    public SettlementGenerationScheduler(
            @Value("${gmepay.settlement.generation.enabled:false}") boolean enabled,
            SettlementBatchJobService job) {
        this.enabled = enabled;
        this.job = job;
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
        if (disabled("ZP0065/ZP0066 detail")) {
            return;
        }
        runDetailSafely("ZP0065");
        runDetailSafely("ZP0066");
    }

    private void runWindowSafely(String fileType, String window) {
        if (disabled(fileType + "/" + window)) {
            return;
        }
        try {
            SettlementBatchEntity batch = job.runWindow(fileType, window);
            log.info("SettlementGenerationScheduler: {} {} → batch {} ({})",
                    fileType, window, batch.getBatchId(), batch.getStatus());
        } catch (Exception e) {
            log.error("SettlementGenerationScheduler: {} {} generation failed: {}",
                    fileType, window, e.getMessage(), e);
        }
    }

    private void runDetailSafely(String fileType) {
        try {
            SettlementBatchEntity batch = job.runDetailWindow(fileType);
            log.info("SettlementGenerationScheduler: {} DETAIL → batch {} ({}, {} record(s))",
                    fileType, batch.getBatchId(), batch.getStatus(), batch.getRecordCount());
        } catch (Exception e) {
            log.error("SettlementGenerationScheduler: {} DETAIL generation failed: {}",
                    fileType, e.getMessage(), e);
        }
    }

    private boolean disabled(String what) {
        if (!enabled) {
            log.debug("SettlementGenerationScheduler: {} skipped (gmepay.settlement.generation.enabled=false)", what);
            return true;
        }
        return false;
    }
}
