package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ApiKeyClient;
import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.client.StatementClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.web.dto.PartnerOverview;
import com.gme.pay.bff.web.dto.PartnerProfile;
import com.gme.pay.bff.web.dto.TransactionDetail;
import com.gme.pay.bff.web.dto.WebhookConfigView;
import com.gme.pay.contracts.BalanceView;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Partner Self-Service Portal endpoints. Each method orchestrates 1-N calls to
 * backend services and returns a UI-shaped DTO scoped to the calling partner.
 *
 * <p>Phase-1 endpoints:
 * <ul>
 *   <li>{@code GET /v1/portal/{partnerId}/overview} — balance + recent activity counter + last settlement
 *   <li>{@code GET /v1/portal/{partnerId}/transactions} — paginated recent transactions
 *   <li>{@code GET /v1/portal/{partnerId}/balance} — prefunding balance view
 * </ul>
 *
 * <p>Phase-C2 endpoints:
 * <ul>
 *   <li>{@code GET /v1/portal/{partnerId}/transactions/{txnId}} — single-txn detail scoped to the partner
 *   <li>{@code GET /v1/portal/{partnerId}/webhooks} — webhook configuration rows
 *   <li>{@code GET /v1/portal/{partnerId}/profile} — partner identity for the Profile page
 * </ul>
 *
 * <p>Phase-C4 endpoints:
 * <ul>
 *   <li>{@code GET /v1/portal/{partnerId}/api-keys} — API key list (PRIMARY + ROTATING)
 *   <li>{@code GET /v1/portal/{partnerId}/statement?from&to} — CSV statement download
 * </ul>
 */
@RestController
@RequestMapping("/v1/portal")
public class PartnerPortalController {

    /** Default number of transactions per portal page. */
    static final int DEFAULT_PAGE_SIZE = 20;

    /** Hard cap to protect upstream from runaway page sizes. */
    static final int MAX_PAGE_SIZE = 100;

    private final TransactionMgmtClient transactions;
    private final PrefundingClient prefunding;
    private final SettlementClient settlement;
    private final ConfigRegistryClient configRegistry;
    private final ApiKeyClient apiKeys;
    private final StatementClient statements;

    public PartnerPortalController(
            TransactionMgmtClient transactions,
            PrefundingClient prefunding,
            SettlementClient settlement,
            ConfigRegistryClient configRegistry,
            ApiKeyClient apiKeys,
            StatementClient statements) {
        this.transactions = transactions;
        this.prefunding = prefunding;
        this.settlement = settlement;
        this.configRegistry = configRegistry;
        this.apiKeys = apiKeys;
        this.statements = statements;
    }

    @GetMapping("/{partnerId}/overview")
    public PartnerOverview overview(@PathVariable String partnerId) {
        com.gme.pay.contracts.BalanceView balance = prefunding.getAdminBalance(partnerId);
        List<TransactionMgmtClient.TransactionSummary> recent =
                transactions.recent(partnerId, DEFAULT_PAGE_SIZE);
        List<SettlementClient.SettlementBatchSummary> batches =
                settlement.recent(partnerId, 1);

        LocalDate lastSettlementDate = batches.isEmpty()
                ? null
                : batches.get(0).settlementDate();

        return new PartnerOverview(partnerId, balance, recent.size(), lastSettlementDate);
    }

    /**
     * UC-10-02: Transaction History — per-txn: timestamp, QR scheme, KRW amount,
     * payer-currency amount, applied FX rate, prefunding deducted (USD), status.
     *
     * <p>Revenue stripping: the returned {@link TransactionMgmtClient.TransactionSummary}
     * records contain NO revenue fields (no {@code fxMarginPct}, no {@code gmeRevenue},
     * no {@code marginRevenueUsd}). Revenue data lives exclusively in the Admin
     * {@code /v1/admin/revenue/*} surface which this controller does not expose.
     */
    @GetMapping("/{partnerId}/transactions")
    public List<TransactionMgmtClient.TransactionSummary> transactions(
            @PathVariable String partnerId,
            @RequestParam(name = "limit", required = false, defaultValue = "20") int limit) {
        int capped = Math.min(Math.max(1, limit), MAX_PAGE_SIZE);
        return transactions.recent(partnerId, capped);
    }

    /**
     * UC-10-03: Transaction Detail — txn id, timestamp, merchant info, KRW amount,
     * payer-ccy amount, applied FX rate + rate timestamp, prefunding deducted, status history.
     *
     * <p>Revenue stripping: the returned {@link TransactionDetail} contains NO revenue
     * fields (no {@code fxMarginPct}, no {@code gmeRevenue}). The BFF's
     * {@code buildDetail} path does not call {@code RevenueLedgerClient} and the
     * {@code TransactionDetail} record does not declare those fields.
     * 404 covers both unknown-txn and wrong-partner to prevent oracle leakage.
     */
    @GetMapping("/{partnerId}/transactions/{txnId}")
    public TransactionDetail transactionDetail(
            @PathVariable String partnerId,
            @PathVariable String txnId) {
        TransactionMgmtClient.TransactionSummary summary = transactions.getTransaction(txnId);
        // 404 covers both "unknown" and "not owned by this partner" — we do NOT
        // leak whether the txn exists under a different partner.
        if (summary == null || !Objects.equals(summary.partnerId(), partnerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no transaction " + txnId + " for partner " + partnerId);
        }
        return buildDetail(summary);
    }

    /**
     * UC-10-01: prefunding balance inquiry.
     * Returns the canonical {@link BalanceView} which carries the current USD balance,
     * threshold, pctOfThreshold and (when the prefunding service supports it) the
     * recent deduction history. Internal revenue fields (FX margin %, GME share) are
     * NEVER present in this response — they only exist in the Admin revenue endpoints.
     */
    @GetMapping("/{partnerId}/balance")
    public BalanceView balance(@PathVariable String partnerId) {
        BalanceView view = prefunding.getAdminBalance(partnerId);
        if (view == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no prefunding balance for partner " + partnerId);
        }
        return view;
    }

    @GetMapping("/{partnerId}/webhooks")
    public List<WebhookConfigView> webhooks(@PathVariable String partnerId) {
        // Phase-1 stub: return 1-2 deterministic rows so the Portal UI can bind.
        // Production: GET notification-webhook/{partnerId}/webhooks.
        return List.of(
                new WebhookConfigView(
                        "https://partner.example.com/" + partnerId + "/webhook/payments",
                        List.of("payment.approved", "payment.failed"),
                        "ACTIVE",
                        Instant.parse("2026-06-09T11:00:00Z")),
                new WebhookConfigView(
                        "https://partner.example.com/" + partnerId + "/webhook/settlements",
                        List.of("settlement.completed"),
                        "ACTIVE",
                        Instant.parse("2026-06-08T22:30:00Z")));
    }

    @GetMapping("/{partnerId}/profile")
    public PartnerProfile profile(@PathVariable String partnerId) {
        ConfigRegistryClient.PartnerSummary partner = configRegistry.getPartner(partnerId);
        if (partner == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no partner with id " + partnerId);
        }
        // Phase-1: synthesize an onboarding timestamp deterministically. In
        // production this comes from config-registry's partner record.
        return new PartnerProfile(
                partner.partnerId(),
                partner.type(),
                partner.settlementCurrency(),
                partner.settlementRoundingMode(),
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    @GetMapping("/{partnerId}/api-keys")
    public List<ApiKeyClient.ApiKeyView> apiKeys(@PathVariable String partnerId) {
        List<ApiKeyClient.ApiKeyView> keys = apiKeys.listForPartner(partnerId);
        return keys == null ? List.of() : keys;
    }

    @GetMapping("/{partnerId}/statement")
    public ResponseEntity<byte[]> statement(
            @PathVariable String partnerId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        if (from == null || to == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "from and to are required");
        }
        if (to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "to must not be before from");
        }
        byte[] body = statements.exportCsv(partnerId, from, to);
        byte[] safeBody = body == null ? new byte[0] : body;
        String filename = "statement-" + from + "-" + to + ".csv";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(safeBody);
    }

    /**
     * Synthesizes a Phase-1 {@link TransactionDetail} from the read-side summary.
     * Mirrors {@code AdminDashboardController#buildDetail} so the Portal UI sees
     * the same shape as the Admin UI.
     */
    private TransactionDetail buildDetail(TransactionMgmtClient.TransactionSummary summary) {
        ConfigRegistryClient.PartnerSummary partner = configRegistry.getPartner(summary.partnerId());
        RoundingMode mode = partner == null ? RoundingMode.HALF_UP : partner.settlementRoundingMode();
        // Real values from transaction-mgmt — the scheme ref / approval / merchant id / approvedAt are
        // the genuine merchant-paid evidence (not "SCH-"/"AP-" placeholders). Settlement booking is
        // locked at settlement time, so booked amount + residual are null on a freshly approved txn.
        return new TransactionDetail(
                summary,
                summary.schemeTxnRef(),
                summary.schemeApprovalCode(),
                summary.prefundingDeductedUsd(),
                summary.approvedAt(),
                null,
                mode,
                null,
                summary.merchantId(),
                null,
                summary.statusHistory(),      // ordered status history (null-safe)
                summary.failureReason(),      // null-safe on older txns
                summary.statusLabel(),        // plain-language status label (null-safe)
                summary.declineReasonText()); // human-readable decline reason (null-safe)
    }
}
