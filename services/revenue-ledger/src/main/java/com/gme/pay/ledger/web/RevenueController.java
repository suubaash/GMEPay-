package com.gme.pay.ledger.web;

import com.gme.pay.ledger.revenue.RevenueAggregate;
import com.gme.pay.ledger.revenue.RevenueRecordService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * GET /v1/revenue — exposes revenue aggregates (FX margin + service charge) for the Admin Portal.
 *
 * <p>This is the public contract this service exposes per INTER_SERVICE_CONTRACTS.md.
 */
@RestController
@RequestMapping("/v1/revenue")
public class RevenueController {

    private final RevenueRecordService service;

    public RevenueController(RevenueRecordService service) {
        this.service = service;
    }

    /**
     * Retrieve revenue aggregate for a partner over a date range.
     *
     * @param partnerId  required; the partner whose revenue is queried
     * @param startDate  start of the range (inclusive, YYYY-MM-DD)
     * @param endDate    end of the range (inclusive, YYYY-MM-DD)
     * @return HTTP 200 with {@link RevenueSummaryResponse}, or HTTP 400 if startDate > endDate
     */
    @GetMapping
    public ResponseEntity<?> getRevenue(
            @RequestParam long partnerId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        if (startDate.isAfter(endDate)) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error_code", "INVALID_DATE_RANGE",
                                 "message", "startDate must be <= endDate"));
        }

        RevenueAggregate agg = service.getRevenueByPartner(partnerId, startDate, endDate);

        RevenueSummaryResponse response = new RevenueSummaryResponse(
                agg.partnerId(),
                agg.schemeId(),
                startDate,
                endDate,
                agg.txnCount(),
                agg.totalFxMarginUsd(),
                agg.totalServiceChargeAmount(),
                agg.serviceChargeCcy()
        );

        return ResponseEntity.ok(response);
    }
}
