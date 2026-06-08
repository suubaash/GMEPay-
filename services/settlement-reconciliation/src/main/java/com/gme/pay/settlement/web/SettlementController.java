package com.gme.pay.settlement.web;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * GET /v1/settlements — returns net or gross settlement summaries per merchant.
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
