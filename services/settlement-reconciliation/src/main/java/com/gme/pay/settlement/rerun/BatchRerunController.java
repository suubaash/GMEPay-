package com.gme.pay.settlement.rerun;

import com.gme.pay.settlement.runlog.BatchRunEntity;
import com.gme.pay.settlement.runlog.BatchRunOutcome;
import com.gme.pay.settlement.runlog.BatchRunRepository;
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
import java.util.List;
import java.util.Map;

/**
 * Operator batch-operations API for OUTBOUND settlement generation (gap <b>T3-4</b>).
 *
 * <pre>
 * POST /v1/settlements/batch/rerun     body { fileType, settlementWindow?, businessDate, operatorId, reason, force? }
 * GET  /v1/settlements/batch/runs      ?outcome=FAILED&amp;limit=50   — the run ledger, newest first
 * GET  /v1/settlements/batch/runs/last-success                     — last success per window ("did last night run?")
 * </pre>
 *
 * <p>Deliberately shaped like {@link ReconRerunController} (its inbound-recon counterpart): thin controller,
 * all decisions in the service, exceptions mapped to status codes here and nowhere else. The difference is
 * the extra {@code 409} — {@link BatchRerunAlreadySucceededException} — which is the "refuse rather than
 * duplicate" half of the gap.
 *
 * <p><b>Authentication.</b> This service currently runs no internal-auth filter of its own (it is an
 * outbound-only client of the gated services, see its {@code application.yml}), so these endpoints inherit
 * whatever the deployment puts in front of the service — in-cluster reachability only, not public. That is
 * stated plainly rather than implied: turning on {@code gmepay.internal-auth.enabled} here would make
 * {@code GMEPAY_INTERNAL_AUTH_SECRET} mandatory for the service to boot, which is a deployment change and is
 * recorded as a follow-up in the fix report rather than smuggled into a batch-ops change.
 */
@RestController
@RequestMapping("/v1/settlements/batch")
public class BatchRerunController {

    /** Hard cap on the ledger read, so nobody can pull the whole table through the API. */
    private static final int MAX_LIMIT = 500;
    private static final int DEFAULT_LIMIT = 50;

    private final BatchRerunService service;
    private final BatchRunRepository runRepository;

    public BatchRerunController(BatchRerunService service, BatchRunRepository runRepository) {
        this.service = service;
        this.runRepository = runRepository;
    }

    /** Re-run one generation window for a named business date. Safe to invoke twice. */
    @PostMapping("/rerun")
    public ResponseEntity<BatchRerunResponse> rerun(@RequestBody BatchRerunRequest request) {
        try {
            return ResponseEntity.ok(service.rerun(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        } catch (BatchRerunAlreadySucceededException e) {
            // 409: well-formed request, state says no. The message names the run that already succeeded.
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    /**
     * The batch-run ledger, newest first — bounded, with no offset parameter so the table cannot be walked.
     *
     * @param outcome optional filter, e.g. {@code FAILED}
     * @param limit   1..500, default 50
     */
    @GetMapping("/runs")
    public ResponseEntity<List<Map<String, Object>>> runs(
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false, defaultValue = "" + DEFAULT_LIMIT) int limit) {
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        PageRequest page = PageRequest.of(0, capped);
        List<BatchRunEntity> rows = (outcome == null || outcome.isBlank())
                ? runRepository.findByOrderByFinishedAtDesc(page)
                : runRepository.findByOutcomeOrderByFinishedAtDesc(
                        outcome.trim().toUpperCase(java.util.Locale.ROOT), page);
        return ResponseEntity.ok(rows.stream().map(BatchRerunController::toView).toList());
    }

    /**
     * Last successful run per window — the "did last night's runs happen?" check the COO audit asked for.
     * Derived from the ledger rather than stored, so it cannot drift from it.
     */
    @GetMapping("/runs/last-success")
    public ResponseEntity<List<Map<String, Object>>> lastSuccess() {
        List<Map<String, Object>> out = runRepository
                .findLastSuccessPerWindow(BatchRunOutcome.SUCCESS.name()).stream()
                .map(r -> Map.<String, Object>of(
                        "fileType", String.valueOf(r[0]),
                        "settlementWindow", String.valueOf(r[1]),
                        "lastSuccessAt", r[2] == null ? "" : ((Instant) r[2]).toString()))
                .toList();
        return ResponseEntity.ok(out);
    }

    /**
     * Projection for the ledger read. Hand-built rather than serialising the entity so the stack excerpt
     * ({@code failure_trace}) is NOT exposed over HTTP — it can carry internal paths and SQL fragments, and
     * an operator who needs it can read the row. The class, message and alert status are what triage needs.
     */
    private static Map<String, Object> toView(BatchRunEntity r) {
        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("runKind", r.getRunKind());
        m.put("fileType", r.getFileType());
        m.put("settlementWindow", r.getSettlementWindow());
        m.put("businessDate", nullSafe(r.getBusinessDate()));
        m.put("outcome", r.getOutcome());
        m.put("triggerSource", r.getTriggerSource());
        m.put("calendarVerdict", r.getCalendarVerdict());
        m.put("batchId", r.getBatchId());
        m.put("batchStatus", r.getBatchStatus());
        m.put("recordCount", r.getRecordCount());
        m.put("failureClass", r.getFailureClass());
        m.put("failureMessage", r.getFailureMessage());
        m.put("alertStatus", r.getAlertStatus());
        m.put("alertError", r.getAlertError());
        m.put("operatorId", r.getOperatorId());
        m.put("reason", r.getReason());
        m.put("startedAt", nullSafe(r.getStartedAt()));
        m.put("finishedAt", nullSafe(r.getFinishedAt()));
        return m;
    }

    private static String nullSafe(LocalDate d) {
        return d == null ? null : d.toString();
    }

    private static String nullSafe(Instant i) {
        return i == null ? null : i.toString();
    }
}
