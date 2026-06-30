package com.gme.pay.ledger.web;

import com.gme.pay.contracts.RevenueSummaryView;
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
 *
 * <p><b>Phase 2 wire shape.</b> The 200 body is the canonical shared {@link RevenueSummaryView}
 * (lib-api-contracts), so ops-partner-bff and the reporting revenue board bind ONE type — including
 * the {@code totalRoundingUsd} column (revenue-ledger IR-3). Money rides as decimal strings per
 * {@code docs/MONEY_CONVENTION.md} via the DTO's {@code @JsonFormat(STRING)} fields. The former
 * service-local {@link RevenueSummaryResponse} is retained only as the source of the aggregate values.
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
     * @return HTTP 200 with {@link RevenueSummaryView}, or HTTP 400 if startDate > endDate
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

        RevenueSummaryView response = new RevenueSummaryView(
                agg.partnerId(),
                agg.schemeId(),
                startDate,
                endDate,
                agg.txnCount(),
                agg.totalFxMarginUsd(),
                agg.totalServiceChargeAmount(),
                agg.serviceChargeCcy(),
                service.getRoundingTotalUsd(startDate, endDate)
        );

        return ResponseEntity.ok(response);
    }
}
