package com.gme.pay.settlement.tieout;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * GET /v1/settlements/tie-out — daily cent-for-cent tie-out report for one business date.
 *
 * <p>Query parameters:
 * <ul>
 *   <li>{@code date} — ISO date (yyyy-MM-dd); defaults to today when omitted</li>
 * </ul>
 *
 * <p>Returns a {@link TieOutReport} proving (1) the settlement engine's internal
 * gross = net + fee + refund balance over the date's persisted batches, and (2) — when the
 * revenue-ledger client is enabled — that the withheld merchant fees equal the ledger's recognised
 * fee revenue. Money rides as decimal strings per {@code docs/MONEY_CONVENTION.md}.
 */
@RestController
@RequestMapping("/v1/settlements")
public class TieOutController {

    private final TieOutService tieOutService;

    public TieOutController(TieOutService tieOutService) {
        this.tieOutService = tieOutService;
    }

    @GetMapping("/tie-out")
    public TieOutReport getTieOut(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date) {

        return tieOutService.report(date != null ? date : LocalDate.now());
    }
}
