package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.bff.client.RevenueLedgerClient;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.web.dto.AdminDashboard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Admin UI endpoints. Each method orchestrates 1-N calls to backend services
 * (via the {@code com.gme.pay.bff.client} interfaces) and combines the results
 * into a UI-shaped DTO.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /v1/admin/dashboard} — counters + today's revenue
 *   <li>{@code GET /v1/admin/partners} — partner list (config-registry)
 *   <li>{@code GET /v1/admin/transactions/recent} — recent transactions across all partners
 *   <li>{@code GET /v1/admin/settlement/recent} — recent settlement batches
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminDashboardController {

    /** Number of recent rows the Admin views pull by default. */
    static final int RECENT_LIMIT = 20;

    private final ConfigRegistryClient configRegistry;
    private final TransactionMgmtClient transactions;
    private final PrefundingClient prefunding;
    private final RevenueLedgerClient revenue;
    private final SettlementClient settlement;

    public AdminDashboardController(
            ConfigRegistryClient configRegistry,
            TransactionMgmtClient transactions,
            PrefundingClient prefunding,
            RevenueLedgerClient revenue,
            SettlementClient settlement) {
        this.configRegistry = configRegistry;
        this.transactions = transactions;
        this.prefunding = prefunding;
        this.revenue = revenue;
        this.settlement = settlement;
    }

    @GetMapping("/dashboard")
    public AdminDashboard dashboard() {
        List<ConfigRegistryClient.PartnerSummary> partners = configRegistry.listPartners();
        List<TransactionMgmtClient.TransactionSummary> recentTxns =
                transactions.recent(null, RECENT_LIMIT);

        int lowBalanceCount = 0;
        for (ConfigRegistryClient.PartnerSummary partner : partners) {
            PrefundingClient.BalanceView balance = prefunding.getBalance(partner.partnerId());
            if (balance != null && balance.belowThreshold()) {
                lowBalanceCount++;
            }
        }

        RevenueLedgerClient.RevenueSummary today = revenue.getSummary(LocalDate.now());
        BigDecimal todayRevenueUsd = today == null ? BigDecimal.ZERO : today.totalRevenueUsd();

        return new AdminDashboard(
                recentTxns.size(),
                partners.size(),
                lowBalanceCount,
                todayRevenueUsd);
    }

    @GetMapping("/partners")
    public List<ConfigRegistryClient.PartnerSummary> partners() {
        return configRegistry.listPartners();
    }

    @GetMapping("/transactions/recent")
    public List<TransactionMgmtClient.TransactionSummary> recentTransactions() {
        return transactions.recent(null, RECENT_LIMIT);
    }

    @GetMapping("/settlement/recent")
    public List<SettlementClient.SettlementBatchSummary> recentSettlements() {
        return settlement.recent(null, RECENT_LIMIT);
    }
}
