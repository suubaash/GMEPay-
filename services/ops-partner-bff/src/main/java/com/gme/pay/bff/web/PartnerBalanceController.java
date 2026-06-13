package com.gme.pay.bff.web;

import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.contracts.BalanceAlertView;
import com.gme.pay.contracts.BalanceView;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Slice 5 (5B.1) prefunding balance pass-throughs for the Admin UI partner
 * page. Kept in its own controller (the {@code PartnerSettlementController}
 * precedent) so each slice surface stays reviewable in isolation; mounts under
 * the same {@code /v1/admin} prefix.
 *
 * <p>Pure pass-throughs: each call delegates to {@link PrefundingClient},
 * which adapts to prefunding's {@code GET /v1/prefunding/{partnerCode}/balance}
 * and {@code GET /v1/prefunding/{partnerCode}/alerts}. Money rides as decimal
 * strings per {@code docs/MONEY_CONVENTION.md} ({@link BalanceView} /
 * {@link BalanceAlertView} serialize {@code BigDecimal} as JSON strings).
 */
@RestController
@RequestMapping("/v1/admin")
public class PartnerBalanceController {

    private final PrefundingClient prefunding;

    public PartnerBalanceController(PrefundingClient prefunding) {
        this.prefunding = prefunding;
    }

    /**
     * The partner's current prefunding balance with pctOfThreshold — the value
     * the Admin UI gauges tier alerts (95/85/70%) against. 404 when the partner
     * has no provisioned balance.
     */
    @GetMapping("/partners/{partnerCode}/balance")
    public BalanceView getBalance(@PathVariable String partnerCode) {
        BalanceView view = prefunding.getAdminBalance(partnerCode);
        if (view == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no prefunding balance for partner '" + partnerCode + "'");
        }
        return view;
    }

    /**
     * Raised tier alerts, newest first (TIER_95 / TIER_85 / TIER_70 / BREACH).
     * 404 when the partner has no provisioned balance; an empty array when it
     * does but nothing has fired.
     */
    @GetMapping("/partners/{partnerCode}/balance-alerts")
    public List<BalanceAlertView> getBalanceAlerts(@PathVariable String partnerCode) {
        if (prefunding.getAdminBalance(partnerCode) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no prefunding balance for partner '" + partnerCode + "'");
        }
        return prefunding.getBalanceAlerts(partnerCode);
    }
}
