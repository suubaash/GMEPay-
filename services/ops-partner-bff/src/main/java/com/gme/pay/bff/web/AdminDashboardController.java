package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.bff.client.RevenueLedgerClient;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.web.dto.AdminDashboard;
import com.gme.pay.bff.web.dto.Page;
import com.gme.pay.bff.web.dto.PartnerCreateRequest;
import com.gme.pay.bff.web.dto.RevenueBreakdown;
import com.gme.pay.bff.web.dto.SettlementBatchDetail;
import com.gme.pay.bff.web.dto.TransactionDetail;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Admin UI endpoints. Each method orchestrates 1-N calls to backend services
 * (via the {@code com.gme.pay.bff.client} interfaces) and combines the results
 * into a UI-shaped DTO.
 *
 * <p>Phase-1 endpoints:
 * <ul>
 *   <li>{@code GET /v1/admin/dashboard} — counters + today's revenue
 *   <li>{@code GET /v1/admin/partners} — partner list (config-registry)
 *   <li>{@code GET /v1/admin/transactions/recent} — recent transactions across all partners
 *   <li>{@code GET /v1/admin/settlement/recent} — recent settlement batches
 * </ul>
 *
 * <p>Phase-C2 endpoints (added so the Admin UI can drop mock data):
 * <ul>
 *   <li>{@code POST   /v1/admin/partners} — create a partner from the Admin form
 *   <li>{@code GET    /v1/admin/partners/{id}} — single partner lookup (404 if unknown)
 *   <li>{@code PUT    /v1/admin/partners/{id}/rounding-mode} — change per-partner rounding rule
 *   <li>{@code GET    /v1/admin/schemes} — scheme list for the Admin scheme table
 *   <li>{@code GET    /v1/admin/transactions} — filtered + paginated search
 *   <li>{@code GET    /v1/admin/transactions/{txnId}} — single-transaction detail (404 if unknown)
 *   <li>{@code GET    /v1/admin/settlement/{batchId}} — single-batch detail (404 if unknown)
 *   <li>{@code GET    /v1/admin/revenue/summary?from&to} — revenue total for a range
 *   <li>{@code GET    /v1/admin/revenue/breakdown?from&to} — by-partner/scheme/currency breakdown
 * </ul>
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminDashboardController {

    /** Number of recent rows the Admin views pull by default. */
    static final int RECENT_LIMIT = 20;

    /** Default page size when the Admin transactions search omits {@code size}. */
    static final int DEFAULT_PAGE_SIZE = 20;

    /** Hard cap on transactions-search page size. */
    static final int MAX_PAGE_SIZE = 200;

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

    @PostMapping("/partners")
    public ResponseEntity<ConfigRegistryClient.PartnerSummary> createPartner(
            @RequestBody PartnerCreateRequest body) {
        ConfigRegistryClient.PartnerSummary created = configRegistry.createPartner(
                new ConfigRegistryClient.PartnerCreateRequest(
                        body.partnerId(),
                        body.type(),
                        body.settlementCurrency(),
                        body.settlementRoundingMode()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/partners/{id}")
    public ConfigRegistryClient.PartnerSummary getPartner(@PathVariable String id) {
        ConfigRegistryClient.PartnerSummary partner = configRegistry.getPartner(id);
        if (partner == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no partner with id " + id);
        }
        return partner;
    }

    @PutMapping("/partners/{id}/rounding-mode")
    public ConfigRegistryClient.PartnerSummary updateRoundingMode(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        String mode = body == null ? null : body.get("mode");
        if (mode == null || mode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "mode is required");
        }
        ConfigRegistryClient.PartnerSummary updated =
                configRegistry.updateRoundingMode(id, mode);
        if (updated == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no partner with id " + id);
        }
        return updated;
    }

    @GetMapping("/schemes")
    public List<ConfigRegistryClient.SchemeSummary> schemes() {
        return configRegistry.listSchemes();
    }

    @GetMapping("/transactions/recent")
    public List<TransactionMgmtClient.TransactionSummary> recentTransactions() {
        return transactions.recent(null, RECENT_LIMIT);
    }

    @GetMapping("/transactions")
    public Page<TransactionMgmtClient.TransactionSummary> searchTransactions(
            @RequestParam(required = false) String partnerId,
            @RequestParam(required = false) String schemeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size <= 0 ? DEFAULT_PAGE_SIZE : size), MAX_PAGE_SIZE);
        TransactionMgmtClient.Page<TransactionMgmtClient.TransactionSummary> upstream =
                transactions.list(new TransactionMgmtClient.Filter(
                        partnerId, schemeId, status, fromDate, toDate, safePage, safeSize));
        return new Page<>(upstream.content(), upstream.page(), upstream.size(), upstream.total());
    }

    @GetMapping("/transactions/{txnId}")
    public TransactionDetail transactionDetail(@PathVariable String txnId) {
        TransactionMgmtClient.TransactionSummary summary = transactions.getTransaction(txnId);
        if (summary == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no transaction with id " + txnId);
        }
        return buildDetail(summary);
    }

    @GetMapping("/settlement/recent")
    public List<SettlementClient.SettlementBatchSummary> recentSettlements() {
        return settlement.recent(null, RECENT_LIMIT);
    }

    @GetMapping("/settlement/{batchId}")
    public SettlementBatchDetail settlementDetail(@PathVariable String batchId) {
        SettlementClient.SettlementBatchDetail upstream = settlement.detail(batchId);
        if (upstream == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no settlement batch with id " + batchId);
        }
        return new SettlementBatchDetail(upstream.batch(), upstream.lines());
    }

    @GetMapping("/revenue/summary")
    public RevenueLedgerClient.RevenueSummary revenueSummary(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return revenue.summaryRange(
                from == null ? LocalDate.now() : from,
                to == null ? LocalDate.now() : to);
    }

    @GetMapping("/revenue/breakdown")
    public RevenueBreakdown revenueBreakdown(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        RevenueLedgerClient.RevenueBreakdown upstream = revenue.breakdown(
                from == null ? LocalDate.now() : from,
                to == null ? LocalDate.now() : to);
        return new RevenueBreakdown(upstream.byPartner(), upstream.byScheme(), upstream.byCurrency());
    }

    /**
     * Synthesizes a Phase-1 {@link TransactionDetail} from the read-side summary.
     * The scheme/approval fields are deterministic derivations of the txn id so
     * the Admin UI drawer can render without booting transaction-mgmt. In
     * production the BFF will fetch the rich detail from transaction-mgmt's
     * {@code GET /v1/transactions/{id}} which already carries these fields.
     */
    private TransactionDetail buildDetail(TransactionMgmtClient.TransactionSummary summary) {
        BigDecimal precise = summary.amount();
        ConfigRegistryClient.PartnerSummary partner = configRegistry.getPartner(summary.partnerId());
        java.math.RoundingMode mode = partner == null
                ? java.math.RoundingMode.HALF_UP
                : partner.settlementRoundingMode();
        // Phase-1: approximate "booked" by rounding precise to 2dp under the partner's mode;
        // residual is precise - booked.
        BigDecimal booked = precise.setScale(2, mode);
        BigDecimal residual = precise.subtract(booked);
        Instant approvedAt = summary.committedAt() == null
                ? null
                : summary.committedAt().minus(2, ChronoUnit.SECONDS);
        return new TransactionDetail(
                summary,
                "SCH-" + summary.txnId(),
                "AP-" + summary.txnId(),
                precise, // prefund deducted in USD — approximated as the txn amount for the stub
                approvedAt,
                booked,
                mode,
                residual);
    }
}
