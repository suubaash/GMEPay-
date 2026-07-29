package com.gme.pay.settlement.corridor;

import com.gme.pay.settlement.persistence.CorridorReconSummaryEntity;
import com.gme.pay.settlement.persistence.CorridorReconSummaryRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Cross-border reconciliation API (GAP T2-2).
 *
 * <pre>
 * POST /v1/settlement/corridor/{scheme}/recon?date=YYYY-MM-DD  — run/re-run the three-way tie-out
 * GET  /v1/settlement/corridor/{scheme}/summary?date=…          — one day's finance summary
 * GET  /v1/settlement/corridor/{scheme}/summary?from=…&to=…     — a period of summaries
 * </pre>
 *
 * <p>Breaks themselves are NOT served here: they live in the existing ops exception queue
 * ({@code GET /v1/settlement/exceptions?batchId=SENDMN-3WAY-YYYYMMDD}, or filtered by
 * {@code matchStatus=RATE_BASIS_VARIANCE}), so the ops resolve / re-run workflow is unchanged.
 *
 * <p>Re-running a date is idempotent — it replaces that date's breaks and summary row.
 */
@RestController
@RequestMapping("/v1/settlement/corridor")
public class CorridorReconController {

    private final List<CorridorThreeWayReconciler> reconcilers;
    private final CorridorReconSummaryRepository summaryRepository;

    public CorridorReconController(List<CorridorThreeWayReconciler> reconcilers,
                                   CorridorReconSummaryRepository summaryRepository) {
        this.reconcilers = reconcilers;
        this.summaryRepository = summaryRepository;
    }

    /**
     * Run (or re-run) the three-way tie-out for one corridor and settlement date.
     *
     * @param scheme corridor lane code, e.g. {@code sendmn} (case-insensitive)
     * @param date   settlement business date
     * @return 200 with the day's summary + break count, or 404 when no such corridor is configured
     *         (e.g. 9Pay, which has no hub payout orchestration yet — register item T4-7)
     */
    @PostMapping("/{scheme}/recon")
    public ResponseEntity<Map<String, Object>> recon(
            @PathVariable("scheme") String scheme,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        Optional<CorridorThreeWayReconciler> reconciler = find(scheme);
        if (reconciler.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        CorridorReconResult result = reconciler.get().reconcile(date);
        return ResponseEntity.ok(Map.of(
                "batchId", result.batchId(),
                "scheme", result.scheme(),
                "settlementDate", result.settlementDate().toString(),
                "lineCount", result.lines().size(),
                "breakCount", result.breaks().size(),
                "summary", CorridorReconSummaryResponse.from(result.summary())));
    }

    /**
     * One day's summary, or a period when {@code from}/{@code to} are supplied.
     *
     * @return 200 with a single summary (or the list for a period); 404 when the date has not been
     *         reconciled yet
     */
    @GetMapping("/{scheme}/summary")
    public ResponseEntity<?> summary(
            @PathVariable("scheme") String scheme,
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(name = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        String lane = scheme.toUpperCase(Locale.ROOT);
        if (from != null && to != null) {
            List<CorridorReconSummaryResponse> series = summaryRepository
                    .findBySchemeAndSettlementDateBetweenOrderBySettlementDateAsc(lane, from, to)
                    .stream()
                    .map(CorridorReconSummaryResponse::from)
                    .toList();
            return ResponseEntity.ok(series);
        }
        if (date == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "supply either date=YYYY-MM-DD or both from= and to="));
        }
        Optional<CorridorReconSummaryEntity> row =
                summaryRepository.findBySettlementDateAndScheme(date, lane);
        return row.<ResponseEntity<?>>map(e -> ResponseEntity.ok(CorridorReconSummaryResponse.from(e)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private Optional<CorridorThreeWayReconciler> find(String scheme) {
        String lane = scheme.toUpperCase(Locale.ROOT);
        return reconcilers.stream().filter(r -> r.scheme().equals(lane)).findFirst();
    }
}
