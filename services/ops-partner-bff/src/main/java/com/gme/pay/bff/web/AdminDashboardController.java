package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.bff.client.RevenueLedgerClient;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.web.dto.AdminDashboard;
import com.gme.pay.bff.web.dto.DraftPartnerRequest;
import com.gme.pay.bff.web.dto.DraftPartnerStep1Request;
import com.gme.pay.bff.web.dto.DraftPartnerStep2Request;
import com.gme.pay.bff.web.dto.Page;
import com.gme.pay.bff.web.dto.PartnerCreateRequest;
import com.gme.pay.bff.web.dto.RevenueBreakdown;
import com.gme.pay.bff.web.dto.SettlementBatchDetail;
import com.gme.pay.bff.web.dto.TransactionDetail;
import com.gme.pay.contracts.PartnerView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
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

    // -------- Slice 1 (1C.2) draft endpoints (ADR-012) -----------------------
    //
    // The Admin UI wizard's Identity step calls these. The BFF is a pass-through
    // (no orchestration with other backend services for now); each call delegates
    // to ConfigRegistryClient which adapts to the upstream
    // POST/PATCH /v1/partners/draft* endpoints on config-registry. The DTOs
    // (DraftPartnerRequest / DraftPartnerStep1Request) live in
    // com.gme.pay.bff.web.dto so the Admin UI binds against BFF-shaped JSON;
    // the BFF then maps these to lib-api-contracts PartnerCommand records before
    // calling config-registry.

    /**
     * Create a partner draft. Mirrors {@code POST /v1/partners/draft} on
     * config-registry; the row appears in {@code partners} with
     * {@code status=ONBOARDING} and a paired change_request in
     * {@code state=DRAFT}. Returns 201 with the canonical {@link PartnerView}
     * (Slice 1 DTO collapse — see {@code docs/PARTNER_SETUP_PLAN.md}).
     */
    @PostMapping("/partners/draft")
    public ResponseEntity<PartnerView> createDraft(@RequestBody DraftPartnerRequest body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        PartnerView created = configRegistry.createDraft(body.toCreateDraft());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Save Step-1 Identity edits onto a draft. Mirrors
     * {@code PATCH /v1/partners/draft/{partnerCode}/step-1}. Returns 200 with
     * the updated {@link PartnerView} carrying the fresh bitemporal stamps.
     */
    @PatchMapping("/partners/draft/{partnerCode}/step-1")
    public PartnerView patchDraftStep1(@PathVariable String partnerCode,
                                       @RequestBody DraftPartnerStep1Request body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return configRegistry.patchDraftStep1(partnerCode, body.toUpdateStep1());
    }

    /**
     * Read the current draft for {@code partnerCode}. Mirrors
     * {@code GET /v1/partners/draft/{partnerCode}}. Returns 404 if no draft
     * exists for the code.
     */
    @GetMapping("/partners/draft/{partnerCode}")
    public PartnerView getDraft(@PathVariable String partnerCode) {
        PartnerView view = configRegistry.getDraft(partnerCode);
        if (view == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no draft for partner '" + partnerCode + "'");
        }
        return view;
    }

    /**
     * List every in-flight draft. Mirrors {@code GET /v1/partners/drafts}.
     * The Admin UI Partners page shows these in a "Drafts in progress" section
     * so operators can resume / hand off mid-flow per ADR-012.
     */
    @GetMapping("/partners/drafts")
    public List<PartnerView> listDrafts() {
        return configRegistry.listDrafts();
    }

    // -------- Slice 2 (2A.1) contact endpoints (PARTNER_SETUP_PLAN §Slice 2) --

    /**
     * Save Step-2 (Contacts) onto a draft — bulk replace. Mirrors
     * {@code PATCH /v1/partners/draft/{partnerCode}/step-2} on config-registry:
     * the body carries the FULL desired contact set; upstream supersedes every
     * current {@code partner_contact} row and inserts the new set in one
     * transaction (SCD-6, ADR-010) with one {@code partner_contact} audit row
     * (ADR-007). Returns 200 with the freshly-inserted current set; upstream
     * 400/404/409 pass through with their messages preserved.
     */
    @PatchMapping("/partners/draft/{partnerCode}/step-2")
    public List<com.gme.pay.contracts.ContactView> patchDraftStep2(
            @PathVariable String partnerCode,
            @RequestBody DraftPartnerStep2Request body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body required");
        }
        return configRegistry.patchDraftStep2(partnerCode, body.toUpdateStep2());
    }

    /**
     * The CURRENT contact set for {@code partnerCode}. Mirrors
     * {@code GET /v1/partners/{partnerCode}/contacts}. A partner with zero
     * contacts returns an empty list; an unknown code surfaces upstream's 404.
     */
    @GetMapping("/partners/{partnerCode}/contacts")
    public List<com.gme.pay.contracts.ContactView> listContacts(@PathVariable String partnerCode) {
        return configRegistry.listContacts(partnerCode);
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
     * Builds the {@link TransactionDetail} from the read-side summary using the REAL values that
     * transaction-mgmt's {@code GET /v1/transactions/{id}} now carries — the scheme txn ref, approval
     * code, merchant id and scheme-approval instant are the genuine evidence the QR scheme paid the
     * merchant (not the former {@code "SCH-"/"AP-"} placeholders). Settlement booking (booked amount +
     * residual) is locked at settlement time, not at payment time, so it is left null on a freshly
     * approved txn rather than derived from the amount; the partner's configured rounding mode is real.
     */
    private TransactionDetail buildDetail(TransactionMgmtClient.TransactionSummary summary) {
        ConfigRegistryClient.PartnerSummary partner = configRegistry.getPartner(summary.partnerId());
        java.math.RoundingMode mode = partner == null
                ? java.math.RoundingMode.HALF_UP
                : partner.settlementRoundingMode();
        return new TransactionDetail(
                summary,
                summary.schemeTxnRef(),
                summary.schemeApprovalCode(),
                summary.prefundingDeductedUsd(),
                summary.approvedAt(),
                null,   // bookedSettlementAmount — locked at settlement time, not payment time
                mode,
                null,   // roundingResidual — locked at settlement time
                summary.merchantId(),
                null,   // merchantName — not persisted on the txn yet (wallet response carries it)
                null);  // statusHistory — not yet tracked
    }
}
