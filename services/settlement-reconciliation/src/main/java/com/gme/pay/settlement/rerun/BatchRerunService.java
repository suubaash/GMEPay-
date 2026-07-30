package com.gme.pay.settlement.rerun;

import com.gme.pay.settlement.batch.SettlementBatchJobService;
import com.gme.pay.settlement.persistence.SettlementBatchEntity;
import com.gme.pay.settlement.runlog.BatchRunEntity;
import com.gme.pay.settlement.runlog.BatchRunExecutor;
import com.gme.pay.settlement.runlog.BatchRunOutcome;
import com.gme.pay.settlement.runlog.BatchRunRecorder;
import com.gme.pay.settlement.runlog.BatchRunRepository;
import com.gme.pay.settlement.runlog.BatchRunTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Operator re-run for OUTBOUND settlement-file generation (gap <b>T3-4</b>: "no rerun tooling").
 *
 * <h2>Relationship to {@link ReconRerunService}</h2>
 * <p>This extends the established re-run pattern rather than inventing a second one. {@link ReconRerunService}
 * covers the INBOUND half (re-diff a ZP0062/ZP0064 result file against a persisted batch); nothing covered
 * the OUTBOUND half, which is the half that produces the file the counterparty acts on — so a failed 05:00
 * ZP0061 could only be retried by waiting 24 hours. Same package, same
 * request/response/service/controller quartet, same {@code operatorId} + {@code reason} discipline, same
 * exception-to-status mapping. Only the verb differs.
 *
 * <h2>Safe to invoke twice — three independent layers</h2>
 * <ol>
 *   <li><b>Refusal.</b> If a SUCCESSFUL run already exists in the {@code batch_runs} ledger for
 *       {@code (fileType, window, businessDate)}, the re-run is refused with 409 unless {@code force=true}.
 *       This is the layer that turns "idempotent" into "and it tells you why nothing happened".</li>
 *   <li><b>Domain idempotency.</b> {@link SettlementBatchJobService#runWindow(String, String, LocalDate)}
 *       uses {@code createOrGet} plus a PENDING-only guard, so even a forced re-run of an already-GENERATED
 *       batch returns the existing batch instead of writing a second file, second set of lines, or second
 *       outbox event.</li>
 *   <li><b>Ledger append.</b> Every attempt — refused, skipped, succeeded or failed — is recorded, so a
 *       double invocation is visible as two rows rather than being indistinguishable from one.</li>
 * </ol>
 *
 * <p>The re-run goes through {@link BatchRunExecutor}, so it is calendar-gated, ledger-recorded and
 * alert-raising exactly like a scheduled run. There is deliberately no "manual runs bypass the checks" path.
 */
@Service
public class BatchRerunService {

    private static final Logger log = LoggerFactory.getLogger(BatchRerunService.class);

    /** Outbound REQUEST files, run per (fileType, window). */
    private static final Set<String> REQUEST_FILES = Set.of("ZP0061", "ZP0063");
    /** Outbound DETAIL files, always window DETAIL. */
    private static final Set<String> DETAIL_FILES = Set.of("ZP0065", "ZP0066");

    private final SettlementBatchJobService job;
    private final BatchRunExecutor runs;
    private final BatchRunRepository runRepository;

    public BatchRerunService(SettlementBatchJobService job,
                             BatchRunExecutor runs,
                             BatchRunRepository runRepository) {
        this.job = job;
        this.runs = runs;
        this.runRepository = runRepository;
    }

    /**
     * Re-run one generation window for one business date.
     *
     * @throws IllegalArgumentException                 on a malformed request (unknown file type, missing date)
     * @throws BatchRerunAlreadySucceededException      when a successful run exists and {@code force} is false
     */
    public BatchRerunResponse rerun(BatchRerunRequest request) {
        String fileType = normaliseFileType(request);
        LocalDate date = requireDate(request);
        String window = resolveWindow(fileType, request.settlementWindow());
        String operatorId = blankToNull(request.operatorId());

        // Layer 1: refuse rather than duplicate.
        if (!request.force()) {
            refuseIfAlreadySucceeded(fileType, window, date);
        }

        log.info("operator batch re-run requested: operator={} fileType={} window={} businessDate={} "
                        + "force={} reason={}",
                operatorId, fileType, window, date, request.force(), request.reason());

        boolean detail = DETAIL_FILES.contains(fileType);
        BatchRunExecutor.RunResult<SettlementBatchEntity> result = runs.execute(
                BatchRunRecorder.KIND_GENERATION, fileType, window, date, BatchRunTrigger.OPERATOR_RERUN,
                () -> detail ? job.runDetailWindow(fileType, date) : job.runWindow(fileType, window, date),
                batch -> new BatchRunExecutor.RunSummary(
                        batch.getBatchId(), batch.getStatus(), batch.getRecordCount()),
                operatorId, request.reason());

        SettlementBatchEntity batch = result.value();
        return new BatchRerunResponse(
                operatorId, fileType, window, date,
                result.outcome().name(),
                result.verdict().name(),
                batch == null ? null : batch.getBatchId(),
                batch == null ? null : batch.getStatus(),
                batch == null ? null : batch.getRecordCount(),
                result.runId(),
                detailFor(result));
    }

    private void refuseIfAlreadySucceeded(String fileType, String window, LocalDate date) {
        if (!runRepository.existsByFileTypeAndSettlementWindowAndBusinessDateAndOutcome(
                fileType, window, date, BatchRunOutcome.SUCCESS.name())) {
            return;
        }
        Optional<BatchRunEntity> existing = runRepository
                .findFirstByFileTypeAndSettlementWindowAndBusinessDateOrderByFinishedAtDesc(
                        fileType, window, date);
        throw new BatchRerunAlreadySucceededException(fileType, window, date,
                existing.map(BatchRunEntity::getId).orElse(null),
                existing.map(BatchRunEntity::getBatchId).orElse(null));
    }

    private static String detailFor(BatchRunExecutor.RunResult<SettlementBatchEntity> result) {
        if (result.outcome() == BatchRunOutcome.FAILED) {
            Throwable f = result.failure();
            return "re-run FAILED: " + (f == null ? "unknown error"
                    : f.getClass().getSimpleName() + ": " + f.getMessage());
        }
        if (result.outcome() == BatchRunOutcome.SKIPPED_NON_BUSINESS_DAY) {
            return "not re-run: the configured business-day calendar declares this date closed";
        }
        return "re-run completed; generation is idempotent per (fileType, businessDate, window), so an "
                + "already-GENERATED batch is returned unchanged rather than regenerated";
    }

    private static String normaliseFileType(BatchRerunRequest request) {
        String raw = blankToNull(request.fileType());
        if (raw == null) {
            throw new IllegalArgumentException("fileType is required "
                    + "(ZP0061 | ZP0063 | ZP0065 | ZP0066)");
        }
        String fileType = raw.trim().toUpperCase(Locale.ROOT);
        if (!REQUEST_FILES.contains(fileType) && !DETAIL_FILES.contains(fileType)) {
            throw new IllegalArgumentException("unsupported fileType '" + fileType
                    + "' — this endpoint re-runs OUTBOUND generation only (ZP0061 | ZP0063 | ZP0065 | "
                    + "ZP0066). Inbound result-file recon is POST /v1/settlements/recon/rerun.");
        }
        return fileType;
    }

    private static LocalDate requireDate(BatchRerunRequest request) {
        if (request.businessDate() == null) {
            throw new IllegalArgumentException("businessDate is required — a re-run without an explicit "
                    + "date would silently mean 'today', which is not the case an operator needs");
        }
        return request.businessDate();
    }

    /**
     * The (fileType, window) pairing is fixed by the ZeroPay spec, so an omitted window is derived rather
     * than rejected — and a window that CONTRADICTS the file type is rejected rather than quietly honoured,
     * because writing the run under the wrong window key would make the ledger's duplicate-run guard and the
     * batch's unique key disagree.
     */
    private static String resolveWindow(String fileType, String requested) {
        String expected = switch (fileType) {
            case "ZP0061" -> "MORNING";
            case "ZP0063" -> "AFTERNOON";
            default -> "DETAIL";
        };
        String given = blankToNull(requested);
        if (given == null) {
            return expected;
        }
        String normalised = given.trim().toUpperCase(Locale.ROOT);
        if (!normalised.equals(expected)) {
            throw new IllegalArgumentException("settlementWindow '" + normalised + "' does not match "
                    + fileType + " (expected " + expected + ")");
        }
        return expected;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
