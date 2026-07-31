package com.gme.pay.payment.web;

import com.gme.pay.payment.dayclose.DayCloseReport;
import com.gme.pay.payment.dayclose.DayCloseReportEntity;
import com.gme.pay.payment.dayclose.DayCloseReportService;
import com.gme.pay.payment.dayclose.DayCloseReportStore;
import com.gme.pay.payment.dayclose.FxExposureReport;
import com.gme.pay.payment.dayclose.FxExposureService;
import com.gme.pay.payment.opsrun.LedgerOpsRunExecutor;
import com.gme.pay.payment.opsrun.LedgerOpsRunTrigger;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The finance day-close and FX-exposure read surface (gap <b>T2-5</b> / CFO#9, CFO#10).
 *
 * <pre>
 *   GET  /internal/ops/day-close?date=YYYY-MM-DD[&amp;strict=true]  -- the stored close for a date
 *   GET  /internal/ops/day-close/history?limit=30                -- recent closes, headline columns only
 *   POST /internal/ops/day-close?date=YYYY-MM-DD                 -- produce (or re-produce) and store it
 *   GET  /internal/ops/fx-exposure?from=&amp;to=                     -- the derived FX position, on demand
 * </pre>
 *
 * <h2>{@code strict=true}</h2>
 * <p>Answers <b>409</b> when the close is not {@code clean}, with the report still in the body — the same
 * contract revenue-ledger's trial-balance endpoint uses, so an automated day-close check can treat a 409 as a
 * hard failure instead of having to parse and interpret the report. The default {@code strict=false} always
 * answers 200 with an explicit {@code clean} flag, so a human reader cannot miss it either.
 *
 * <h2>Authorisation</h2>
 * <p>Mounted under {@code /internal/**}, which {@code SandboxSurfaceInternalAuthConfig} gates
 * <b>unconditionally</b> with the platform internal token ({@code X-Gme-Internal}) — including when no secret is
 * configured, in which case every caller is refused 401. Fail-closed: this is the whole platform's daily money
 * position.
 */
@RestController
@RequestMapping("/internal/ops")
@Tag(name = "Day close & FX exposure (internal)",
        description = "Three-way daily close and derived FX position (gap T2-5)")
public class DayCloseController {

    private final DayCloseReportService dayCloseService;
    private final DayCloseReportStore store;
    private final FxExposureService fxExposureService;

    public DayCloseController(DayCloseReportService dayCloseService, DayCloseReportStore store,
                              FxExposureService fxExposureService) {
        this.dayCloseService = dayCloseService;
        this.store = store;
        this.fxExposureService = fxExposureService;
    }

    /**
     * The stored close for {@code date} (defaults to the last closeable date).
     *
     * <p>404 when that date has never been closed — deliberately NOT an on-the-fly computation, because a close
     * read must return the artifact that was produced and reviewed, not a fresh one whose numbers may have moved.
     * Use {@code POST} to produce it.
     */
    @GetMapping("/day-close")
    @Operation(summary = "The stored three-way day-close artifact for a business date")
    public ResponseEntity<?> dayClose(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean strict) {
        LocalDate businessDate = date != null ? date : dayCloseService.defaultCloseDate();
        Optional<DayCloseReport> stored = store.find(businessDate);
        if (stored.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error_code", "DAY_CLOSE_NOT_PRODUCED",
                    "message", "no day-close has been produced for " + businessDate
                            + "; POST /internal/ops/day-close?date=" + businessDate + " to produce it"));
        }
        DayCloseReport report = stored.get();
        if (strict && !report.clean()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(report);
        }
        return ResponseEntity.ok(report);
    }

    /** Recent closes, newest first — headline columns only, so a reader can spot which days need opening. */
    @GetMapping("/day-close/history")
    @Operation(summary = "Recent day closes (headline columns, newest first)")
    public List<DayCloseHistoryRow> history(@RequestParam(defaultValue = "30") int limit) {
        return store.history(limit).stream().map(DayCloseHistoryRow::from).toList();
    }

    /**
     * Produce (or re-produce) and store the close for {@code date}, attributed to the caller.
     *
     * <p>Goes through the same {@code LedgerOpsRunExecutor} wrapper as the scheduled run, so an operator close is
     * recorded in {@code ledger_ops_runs} and alerts on failure identically.
     */
    @PostMapping("/day-close")
    @Operation(summary = "Produce and store the day-close for a business date")
    public ResponseEntity<?> produce(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestHeader(name = "X-Operator-Id", required = false) String operatorId) {
        LocalDate businessDate = date != null ? date : dayCloseService.defaultCloseDate();
        LedgerOpsRunExecutor.RunResult<DayCloseReport> result =
                dayCloseService.run(businessDate, LedgerOpsRunTrigger.OPERATOR, operatorId);
        if (result.succeeded()) {
            return ResponseEntity.ok(result.value());
        }
        Throwable failure = result.failure();
        return ResponseEntity.internalServerError().body(Map.of(
                "error_code", "DAY_CLOSE_RUN_FAILED",
                "message", failure == null ? "day-close run failed" : String.valueOf(failure),
                "ledgerOpsRunId", result.runId() == null ? "UNWRITTEN" : result.runId()));
    }

    /**
     * The derived FX position for {@code [from, to]} (defaults to the configured rolling window ending at the
     * last closeable date).
     *
     * <p>Computed on request rather than read from storage: unlike the close, this is a measurement over a window
     * the caller chooses, and there is no signed artifact for it. The same date's position is embedded in the
     * persisted close.
     */
    @GetMapping("/fx-exposure")
    @Operation(summary = "Net open FX position per currency, derived from recorded transactions")
    public ResponseEntity<?> fxExposure(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : dayCloseService.defaultCloseDate();
        LocalDate start = from != null ? from : end.minusDays(fxExposureService.windowDays() - 1L);
        if (start.isAfter(end)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error_code", "INVALID_DATE_RANGE",
                    "message", "from must be <= to"));
        }
        FxExposureReport report = fxExposureService.measure(start, end);
        return ResponseEntity.ok(report);
    }

    /** One row of the close history: enough to decide whether to open the full artifact. */
    public record DayCloseHistoryRow(
            LocalDate businessDate,
            Instant generatedAt,
            String triggerSource,
            boolean clean,
            int varianceCount,
            int unresolvedDecisionCount,
            int unavailableLegCount) {

        static DayCloseHistoryRow from(DayCloseReportEntity e) {
            return new DayCloseHistoryRow(e.getBusinessDate(), e.getGeneratedAt(), e.getTriggerSource(),
                    e.isClean(), e.getVarianceCount(), e.getUnresolvedDecisionCount(),
                    e.getUnavailableLegCount());
        }
    }
}
