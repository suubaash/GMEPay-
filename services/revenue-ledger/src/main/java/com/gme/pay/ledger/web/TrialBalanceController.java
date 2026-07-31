package com.gme.pay.ledger.web;

import com.gme.pay.ledger.persistence.TrialBalanceService;
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
 * {@code GET /v1/journals/trial-balance} — the period trial balance over the posted double-entry
 * journals: per account and per currency debit/credit totals, plus the proof that total debits equal
 * total credits (T2-4 / CFO#7). This is the artifact a day-close or audit needs; the per-journal DR/CR
 * listing stays on the sibling {@link JournalViewController} under the same base path.
 *
 * <pre>
 *   GET /v1/journals/trial-balance?startDate=2026-07-01&endDate=2026-07-31[&strict=true]
 *
 *   200 OK   TrialBalanceView (see that type for the wire shape)
 *   400 Bad Request   startDate > endDate, or a param missing/unparseable
 *   409 Conflict      strict=true AND the period does not balance (body is the same
 *                     TrialBalanceView, so the caller still sees which currency is off)
 * </pre>
 *
 * <p><b>Failing loudly.</b> The default {@code strict=false} always returns 200 with an explicit
 * {@code balanced} flag and a populated {@code imbalances} list (and the service logs at ERROR), so a
 * human reader cannot miss an imbalance. Automated day-close checks should pass {@code strict=true} and
 * treat the 409 as a hard failure rather than having to parse the body.
 */
@RestController
@RequestMapping("/v1/journals")
public class TrialBalanceController {

    private final TrialBalanceService trialBalanceService;

    public TrialBalanceController(TrialBalanceService trialBalanceService) {
        this.trialBalanceService = Objects.requireNonNull(trialBalanceService, "trialBalanceService required");
    }

    @GetMapping("/trial-balance")
    public ResponseEntity<?> trialBalance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "false") boolean strict) {

        if (startDate.isAfter(endDate)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error_code", "INVALID_DATE_RANGE",
                    "message", "startDate must be <= endDate"));
        }

        TrialBalanceView view = trialBalanceService.compute(startDate, endDate);
        if (strict && !view.balanced()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(view);
        }
        return ResponseEntity.ok(view);
    }
}
