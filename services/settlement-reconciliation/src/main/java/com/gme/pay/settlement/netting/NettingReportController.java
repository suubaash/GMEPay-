package com.gme.pay.settlement.netting;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

/**
 * The multilateral netting report (GAP T4-5).
 *
 * <pre>
 * GET /v1/settlements/netting?from=YYYY-MM-DD&amp;to=YYYY-MM-DD
 * </pre>
 *
 * <p>This is the surface that gives {@code MultilateralNettingCalculator} a production caller. It is
 * a <b>report</b>: every response carries {@code applied=false} plus
 * {@link NettingReportResponse#REPORTING_ONLY_NOTE}, because no prefunding balance, settlement file or
 * funding instruction is derived from these figures and doing so is a treasury/commercial decision, not
 * a missing implementation. Read-only; the report recomputes from the persisted
 * {@code corridor_recon_summary} rows on every call and stores nothing.
 */
@RestController
@RequestMapping("/v1/settlements")
public class NettingReportController {

    private final NettingReportService reportService;

    public NettingReportController(NettingReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/netting")
    public NettingReportResponse netting(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        try {
            return reportService.report(from, to);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
