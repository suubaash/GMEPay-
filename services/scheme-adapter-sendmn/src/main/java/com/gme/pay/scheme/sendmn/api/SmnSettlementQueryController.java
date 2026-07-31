package com.gme.pay.scheme.sendmn.api;

import com.gme.pay.scheme.sendmn.dto.DailySettlementResponse;
import com.gme.pay.scheme.sendmn.settlement.SmnSettlementQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * Internal <b>read-only</b> settlement-record surface for the SendMN adapter.
 *
 * <p>{@code GET /internal/scheme/sendmn/settlement/daily?date=YYYY-MM-DD} returns this adapter's
 * own record of the payments SendMN confirmed on that date, each with the SendMN-registered rate
 * it was confirmed at and the resulting USD {@code SETTLEMENT_AMOUNT} GME owes SendMN.
 *
 * <p>Consumed by settlement-reconciliation's SENDMN three-way tie-out (GAP T2-2): our transaction
 * records ↔ our prefunding USD movements ↔ this scheme-side record. It is deliberately NOT a
 * partner statement — SendMN's own recon file format is unresolved (external gate O4) — so no
 * partner-supplied numbers are implied by anything here.
 *
 * <p>No write endpoints, no scheme calls: the query cannot alter payment state.
 */
@RestController
@RequestMapping("/internal/scheme/sendmn/settlement")
public class SmnSettlementQueryController {

    private final SmnSettlementQueryService service;

    public SmnSettlementQueryController(SmnSettlementQueryService service) {
        this.service = service;
    }

    /**
     * GET /internal/scheme/sendmn/settlement/daily — APPROVED payments recorded for one settlement
     * date (KST business day by default; see {@code gmepay.scheme.sendmn.settlement-zone}).
     *
     * @param date settlement business date, ISO-8601 ({@code 2026-07-28})
     * @return 200 with the day's confirmed rows (empty list when the day had none)
     */
    @GetMapping("/daily")
    public ResponseEntity<DailySettlementResponse> daily(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(service.confirmedOn(date));
    }
}
