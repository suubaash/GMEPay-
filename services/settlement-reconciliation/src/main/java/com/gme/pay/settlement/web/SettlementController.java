package com.gme.pay.settlement.web;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /v1/settlements — a <b>PROJECTION</b> of net/gross settlement figures per merchant for ONE date,
 * recomputed on the fly from unbatched approved transactions.
 *
 * <h2>This is not a settlement statement (GAP T4-5)</h2>
 * It reads neither {@code settlement_batches} nor {@code settlement_lines}. It answers "what would a
 * batch for this date look like if one were generated now?" — which is a useful pre-generation check
 * and is <em>not</em> a record of what was settled:
 * <ul>
 *   <li>it covers one date, so it can never produce a period statement;</li>
 *   <li>it has no batch id and no lifecycle status, because it corresponds to no persisted batch;</li>
 *   <li>its figures diverge from the books as soon as a batch is booked — booking applies the
 *       partner's Addendum-001 rounding mode and nets cross-date refund claw-backs, and this does
 *       neither.</li>
 * </ul>
 * For the settled record — per-batch detail, date-ranged lists and the partner-facing statement, all
 * from the persisted tables with real status and an honest transmission state — use
 * {@link SettlementBatchController}. Consumers that need "what was settled" must not read this
 * endpoint: {@code ops-partner-bff} used to, which is why it had to synthesise batch ids and hardcode
 * {@code status=COMPLETED}.
 *
 * <p>Query parameters (all optional):
 * <ul>
 *   <li>{@code date}            — ISO date (yyyy-MM-dd); defaults to today</li>
 *   <li>{@code merchantId}      — filter by merchant</li>
 *   <li>{@code settlementType}  — 'N' (net/domestic) or 'G' (gross/international)</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/settlements")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @GetMapping
    public List<SettlementResponse> getSettlements(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date,

            @RequestParam(required = false)
            String merchantId,

            @RequestParam(required = false)
            Character settlementType) {

        SettlementQueryRequest query = new SettlementQueryRequest(date, merchantId, settlementType);
        return settlementService.getSettlements(query);
    }
}
