package com.gme.pay.ledger.web;

import com.gme.pay.ledger.persistence.RevenueJournalReconciliationService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * {@code GET /v1/revenue/journal-reconciliation} — the finance-team self-check that revenue RECORDS and
 * double-entry JOURNAL lines agree for a period, and an explicit list of what is recorded but could not
 * be journalled (T2-4 / CFO#4, CFO#7). Sits under the same {@code /v1/revenue} base path as the
 * aggregate ({@link RevenueController}) and capture ({@link RevenueCaptureController}) surfaces.
 *
 * <pre>
 *   GET /v1/revenue/journal-reconciliation?startDate=2026-07-01&endDate=2026-07-31[&strict=true]
 *
 *   200 OK   RevenueJournalReconciliationView (see that type for the wire shape)
 *   400 Bad Request   startDate > endDate, or a param missing/unparseable
 *   409 Conflict      strict=true AND the period is not clean (body is the same view, so the
 *                     caller still sees exactly which references / streams / components are off)
 * </pre>
 *
 * <p>Pair it with {@code GET /v1/journals/trial-balance}: that proves the journal balances, this proves
 * it is complete. Note that {@code clean} stays false while any unmapped component carries money — see
 * {@link RevenueJournalReconciliationView} — so {@code strict=true} 409s until the outstanding
 * account-code decision is made. That is intentional: the books are incomplete until then.
 */
@RestController
@RequestMapping("/v1/revenue")
public class RevenueJournalReconciliationController {

    private final RevenueJournalReconciliationService reconciliationService;

    public RevenueJournalReconciliationController(RevenueJournalReconciliationService reconciliationService) {
        this.reconciliationService = Objects.requireNonNull(reconciliationService, "reconciliationService required");
    }

    @GetMapping("/journal-reconciliation")
    public ResponseEntity<?> reconcile(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "false") boolean strict) {

        if (startDate.isAfter(endDate)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error_code", "INVALID_DATE_RANGE",
                    "message", "startDate must be <= endDate"));
        }

        RevenueJournalReconciliationView view = reconciliationService.reconcile(startDate, endDate);
        if (strict && !view.clean()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(view);
        }
        return ResponseEntity.ok(view);
    }
}
