package com.gme.pay.scheme.zeropay.api;

import com.gme.pay.scheme.zeropay.adapter.model.BatchFile;
import com.gme.pay.scheme.zeropay.adapter.model.BatchType;
import com.gme.pay.scheme.zeropay.batch.ZeroPayBatchScheduler;
import com.gme.pay.scheme.zeropay.ops.ZpBatchRunEntity;
import com.gme.pay.scheme.zeropay.ops.ZpBatchRunExecutor;
import com.gme.pay.scheme.zeropay.ops.ZpBatchRunOutcome;
import com.gme.pay.scheme.zeropay.ops.ZpBatchRunRepository;
import com.gme.pay.scheme.zeropay.ops.ZpBatchRunTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Manual ZP00xx batch trigger and run-ledger read surface (gap <b>T3-4</b>).
 *
 * <pre>
 * POST /internal/scheme/zeropay/batch/rerun   body { batchType, businessDate, operatorId, reason, force? }
 * GET  /internal/scheme/zeropay/batch/runs    ?outcome=FAILED&amp;limit=50
 * GET  /internal/scheme/zeropay/batch/runs/last-success
 * </pre>
 *
 * <p>The COO audit noted the adapter had <b>no manual batch-trigger controller at all</b> ({@code api/} held
 * only {@code ZeroPaySchemeController}, {@code RegistrationStatusController} and {@code DevDataController}),
 * so a failed 02:00 ZP0011 could only be retried by waiting for the next day's cron — while the §8.2
 * prerequisite gate kept that day's settlement requests blocked.
 *
 * <p>Mounted under {@code /internal/**}, which this service's {@code gmepay.internal-auth.path-patterns}
 * already gates, so it requires the {@code X-Gme-Internal} shared secret and is fail-closed on a blank one.
 * This is a deliberately powerful endpoint — it regenerates and re-transmits files KFTC acts on — so
 * inheriting the existing gate rather than inventing a new one matters.
 *
 * <p>Shaped like settlement-reconciliation's {@code BatchRerunController}: thin controller, all decisions in
 * the shared run path, and the same "refuse rather than duplicate" 409.
 */
@RestController
@RequestMapping("/internal/scheme/zeropay/batch")
@ConditionalOnBean(ZeroPayBatchScheduler.class)
public class ZeroPayBatchRerunController {

    private static final int MAX_LIMIT = 500;
    private static final int DEFAULT_LIMIT = 50;

    private static final Logger log = LoggerFactory.getLogger(ZeroPayBatchRerunController.class);

    private final ZeroPayBatchScheduler scheduler;
    private final ZpBatchRunRepository runRepository;

    public ZeroPayBatchRerunController(ZeroPayBatchScheduler scheduler,
                                       ZpBatchRunRepository runRepository) {
        this.scheduler = scheduler;
        this.runRepository = runRepository;
    }

    /**
     * Operator request to (re)generate one ZP00xx window.
     *
     * @param batchType    ZP0011 | ZP0021 | ZP0061 | ZP0063 | ZP0065 | ZP0066
     * @param businessDate required — a trigger with no date would silently mean "today"
     * @param force        regenerate even though a successful run already exists for these coordinates
     */
    public record RerunRequest(String batchType, LocalDate businessDate, String operatorId, String reason,
                               boolean force) {
    }

    /** Result of a manual trigger. */
    public record RerunResponse(String operatorId, String batchType, LocalDate businessDate, String outcome,
                                String calendarVerdict, Integer recordCount, Long runId, String detail) {
    }

    @PostMapping("/rerun")
    public ResponseEntity<RerunResponse> rerun(@RequestBody RerunRequest request) {
        BatchType type;
        try {
            type = parseType(request.batchType());
            requireDate(request.businessDate());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        LocalDate date = request.businessDate();
        String operatorId = blankToNull(request.operatorId());

        // Refuse rather than duplicate. Regenerating a file KFTC already holds is a money-affecting act, so
        // it must be a deliberate, separately-recorded decision (force=true) rather than an accident of
        // clicking twice.
        if (!request.force() && runRepository.existsByBatchTypeAndBusinessDateAndOutcome(
                type.name(), date, ZpBatchRunOutcome.SUCCESS.name())) {
            Optional<ZpBatchRunEntity> existing = runRepository
                    .findFirstByBatchTypeAndBusinessDateOrderByFinishedAtDesc(type.name(), date);
            log.warn("refusing ZeroPay batch re-run of {} for {}: already succeeded (zp_batch_runs.id={})",
                    type, date, existing.map(ZpBatchRunEntity::getId).orElse(null));
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        log.info("operator ZeroPay batch re-run: operator={} batchType={} businessDate={} force={} reason={}",
                operatorId, type, date, request.force(), request.reason());

        ZpBatchRunExecutor.RunResult<BatchFile> result = scheduler.runWindow(
                type, ZpBatchRunTrigger.OPERATOR_RERUN, date, operatorId, request.reason());

        if (result == null) {
            // Only reachable on a legacy unit-slice wiring with no run ledger; in a real deployment the
            // operator path never hits the feature gate.
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        BatchFile file = result.value();
        return ResponseEntity.ok(new RerunResponse(
                operatorId, type.name(), date,
                result.outcome().name(),
                result.verdict().name(),
                file == null ? null : file.recordCount(),
                result.runId(),
                detailFor(result)));
    }

    /** The run ledger, newest first — bounded, no offset parameter so the table cannot be walked. */
    @GetMapping("/runs")
    public ResponseEntity<List<Map<String, Object>>> runs(
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        PageRequest page = PageRequest.of(0, capped);
        List<ZpBatchRunEntity> rows = (outcome == null || outcome.isBlank())
                ? runRepository.findByOrderByFinishedAtDesc(page)
                : runRepository.findByOutcomeOrderByFinishedAtDesc(
                        outcome.trim().toUpperCase(Locale.ROOT), page);
        return ResponseEntity.ok(rows.stream().map(ZeroPayBatchRerunController::toView).toList());
    }

    /** Last successful run per batch type — "did last night's six windows happen?". */
    @GetMapping("/runs/last-success")
    public ResponseEntity<List<Map<String, Object>>> lastSuccess() {
        List<Map<String, Object>> out = runRepository
                .findLastSuccessPerType(ZpBatchRunOutcome.SUCCESS.name()).stream()
                .map(r -> Map.<String, Object>of(
                        "batchType", String.valueOf(r[0]),
                        "lastSuccessAt", r[1] == null ? "" : ((Instant) r[1]).toString()))
                .toList();
        return ResponseEntity.ok(out);
    }

    private static String detailFor(ZpBatchRunExecutor.RunResult<BatchFile> result) {
        if (result.outcome() == ZpBatchRunOutcome.FAILED) {
            Throwable f = result.failure();
            return "re-run FAILED: " + (f == null ? "unknown error"
                    : f.getClass().getSimpleName() + ": " + f.getMessage());
        }
        if (result.outcome() == ZpBatchRunOutcome.SKIPPED_NON_BUSINESS_DAY) {
            return "not re-run: the configured business-day calendar declares this date closed";
        }
        return "re-run completed: file regenerated and re-transmitted";
    }

    private static BatchType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("batchType is required");
        }
        BatchType type;
        try {
            type = BatchType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown batchType '" + raw + "'");
        }
        if (!ZeroPayBatchScheduler.OUTBOUND_GENERATED.contains(type)) {
            throw new IllegalArgumentException("batchType " + type + " is not an OUTBOUND generated window; "
                    + "this endpoint triggers ZP0011 | ZP0021 | ZP0061 | ZP0063 | ZP0065 | ZP0066 only");
        }
        return type;
    }

    private static void requireDate(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("businessDate is required — a trigger without an explicit "
                    + "date would silently mean 'today', which is not the case an operator needs");
        }
    }

    /**
     * Projection for the ledger read. Hand-built so the stack excerpt ({@code failure_trace}) is NOT exposed
     * over HTTP — it can carry internal paths and SQL fragments. Class, message and alert status are what
     * triage actually needs.
     */
    private static Map<String, Object> toView(ZpBatchRunEntity r) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("batchType", r.getBatchType());
        m.put("businessDate", r.getBusinessDate() == null ? null : r.getBusinessDate().toString());
        m.put("outcome", r.getOutcome());
        m.put("triggerSource", r.getTriggerSource());
        m.put("calendarVerdict", r.getCalendarVerdict());
        m.put("recordCount", r.getRecordCount());
        m.put("transferred", r.getTransferred());
        m.put("failureClass", r.getFailureClass());
        m.put("failureMessage", r.getFailureMessage());
        m.put("alertStatus", r.getAlertStatus());
        m.put("alertError", r.getAlertError());
        m.put("operatorId", r.getOperatorId());
        m.put("reason", r.getReason());
        m.put("startedAt", r.getStartedAt() == null ? null : r.getStartedAt().toString());
        m.put("finishedAt", r.getFinishedAt() == null ? null : r.getFinishedAt().toString());
        return m;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
