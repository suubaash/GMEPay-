package com.gme.pay.bff.web;

import com.gme.pay.bff.client.ApiKeyClient;
import com.gme.pay.bff.client.ConfigRegistryClient;
import com.gme.pay.bff.client.PortalWebhookClient;
import com.gme.pay.bff.client.PrefundingClient;
import com.gme.pay.bff.client.SandboxKeyClient;
import com.gme.pay.bff.client.SettlementClient;
import com.gme.pay.bff.client.StatementClient;
import com.gme.pay.bff.client.TransactionMgmtClient;
import com.gme.pay.bff.web.dto.PartnerOverview;
import com.gme.pay.bff.web.dto.PartnerProfile;
import com.gme.pay.bff.web.dto.TransactionDetail;
import com.gme.pay.bff.web.dto.WebhookConfigView;
import com.gme.pay.contracts.BalanceView;
import com.gme.pay.contracts.PartnerView;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 *   <li>{@code GET /v1/portal/{partnerId}/api-keys} — API key list (PRODUCTION + SANDBOX rosters)
 *   <li>{@code GET /v1/portal/{partnerId}/statement?from&to} — CSV statement download
 * </ul>
 *
 * <h2>Every read is real data (gap register T1-3)</h2>
 * <p>Each page here reads the service that OWNS the fact, and shows nothing when that service has
 * nothing:
 * <ul>
 *   <li>overview / balance — prefunding ({@code GMEPAY_PREFUNDING_CLIENT=rest} on every deploy
 *       target, so real partner codes resolve instead of only {@code partner_test_00*})</li>
 *   <li>transactions / statement — transaction-mgmt (the CSV is built from persisted rows, not five
 *       hardcoded {@code TXN-100x} samples)</li>
 *   <li>api-keys — auth-identity's {@code api_keys} registry (not two fabricated
 *       {@code gpk_live_…} keys)</li>
 *   <li>webhooks — notification-webhook's endpoint registry (not two inline
 *       {@code partner.example.com} rows)</li>
 *   <li>profile — config-registry, with {@code onboardedAt} = the real {@code go_live_at} (not one
 *       constant shared by every partner)</li>
 * </ul>
 * <p>Facts no service records ({@code lastUsedAt} on a key, {@code lastDeliveredAt} on a webhook, a
 * key's {@code name}/{@code scopes}, {@code onboardedAt} before activation) are returned as
 * {@code null}/empty and rendered as an em dash — never back-filled with a plausible value.
 *
 * <h2>Tenant isolation (gap register T0-4 — cross-partner IDOR)</h2>
 * <p>The {@code {partnerId}} path segment is <b>caller-supplied and is not an identity</b>. Every
 * handler here first calls {@link OpsRbacGuard#requirePartnerScope(String)}, which authorizes the
 * path only when it matches the partner claim of the verified access token — or when the caller is
 * a platform operator holding the explicit cross-partner read permission. Previously the portal
 * trusted the path segment (and the UI's {@code X-Partner-Id} header, sourced from
 * {@code localStorage}) on an unauthenticated BFF, so partner A could read partner B's balances,
 * transactions, profile, API keys and CSV statements by editing a URL, and could mint sandbox keys
 * in B's name.
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
    private final SandboxKeyClient sandboxKeys;
    private final StatementClient statements;
    private final PortalWebhookClient webhooks;
    private final OpsRbacGuard rbac;

    public PartnerPortalController(
            TransactionMgmtClient transactions,
            PrefundingClient prefunding,
            SettlementClient settlement,
            ConfigRegistryClient configRegistry,
            ApiKeyClient apiKeys,
            SandboxKeyClient sandboxKeys,
            StatementClient statements,
            PortalWebhookClient webhooks,
            OpsRbacGuard rbac) {
        this.transactions = transactions;
        this.prefunding = prefunding;
        this.settlement = settlement;
        this.configRegistry = configRegistry;
        this.apiKeys = apiKeys;
        this.sandboxKeys = sandboxKeys;
        this.statements = statements;
        this.webhooks = webhooks;
        this.rbac = rbac;
    }

    @GetMapping("/{partnerId}/overview")
    public PartnerOverview overview(@PathVariable String partnerId) {
        rbac.requirePartnerScope(partnerId);
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
        rbac.requirePartnerScope(partnerId);
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
        rbac.requirePartnerScope(partnerId);
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
        rbac.requirePartnerScope(partnerId);
        BalanceView view = prefunding.getAdminBalance(partnerId);
        if (view == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no prefunding balance for partner " + partnerId);
        }
        return view;
    }

    /**
     * The partner's REAL webhook endpoints, read from notification-webhook's endpoint registry
     * via {@link com.gme.pay.bff.client.PortalWebhookClient} (gap register T1-3).
     *
     * <p>This handler previously built two rows inline — {@code partner.example.com/{code}/webhook/
     * payments} and {@code .../settlements}, both {@code ACTIVE}, with a literal
     * {@code Instant.parse("2026-06-09T11:00:00Z")} last-delivery — so every partner saw the same
     * two nonexistent endpoints under a domain nobody owns, and the page never consulted the
     * service that actually delivers webhooks.
     *
     * <p>Empty list = this partner has no registered endpoints (or the registry is unreachable);
     * the UI renders its "No webhooks configured" empty state. {@code lastDeliveredAt} is null
     * because notification-webhook exposes no per-endpoint last-delivery read.
     *
     * <p>READ-ONLY: URL / event-type / secret-rotation writes remain the Phase-2 self-serve
     * surface pending the T1-5 product decision.
     */
    @GetMapping("/{partnerId}/webhooks")
    public List<WebhookConfigView> webhooks(@PathVariable String partnerId) {
        rbac.requirePartnerScope(partnerId);
        List<WebhookConfigView> configs = webhooks.listForPartner(partnerId);
        return configs == null ? List.of() : configs;
    }

    /**
     * The partner's own registry record (gap register T1-3).
     *
     * <p>{@code onboardedAt} is now the REAL first-activation instant — V025
     * {@code partners.go_live_at}, carried on {@link com.gme.pay.contracts.PartnerView#goLiveAt()}.
     * It was previously a constant {@code Instant.parse("2026-01-01T00:00:00Z")} returned for
     * <em>every</em> partner. A partner that has not yet gone live has no activation instant, so the
     * field is {@code null} and the UI renders an em dash — deliberately NOT back-filled from
     * {@code validFrom}/{@code recordedAt}, which move on every registry edit and would read as a
     * plausible but wrong onboarding date.
     */
    @GetMapping("/{partnerId}/profile")
    public PartnerProfile profile(@PathVariable String partnerId) {
        rbac.requirePartnerScope(partnerId);
        // Preferred path: the canonical view, which carries goLiveAt.
        PartnerView view = configRegistry.getPartnerView(partnerId);
        if (view != null) {
            return PartnerProfile.fromView(view, view.goLiveAt());
        }
        // Upstreams that only serve the legacy four-field summary: still a real profile, but the
        // activation instant is not available on that shape -> honestly absent, never invented.
        ConfigRegistryClient.PartnerSummary partner = configRegistry.getPartner(partnerId);
        if (partner == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "no partner with id " + partnerId);
        }
        return new PartnerProfile(
                partner.partnerId(),
                partner.type(),
                partner.settlementCurrency(),
                partner.settlementRoundingMode(),
                null);
    }

    @GetMapping("/{partnerId}/api-keys")
    public List<ApiKeyClient.ApiKeyView> apiKeys(@PathVariable String partnerId) {
        rbac.requirePartnerScope(partnerId);
        List<ApiKeyClient.ApiKeyView> keys = apiKeys.listForPartner(partnerId);
        return keys == null ? List.of() : keys;
    }

    /**
     * Self-serve SANDBOX key issuance for the Partner Portal "Get Started"
     * flow. A logged-in partner mints their own sandbox credential — no
     * account-manager / 4-eyes step (that gate is reserved for PRODUCTION keys
     * and is NOT reachable here).
     *
     * <p>Returns 201 with the ONE-TIME plaintext {@code apiKey}: it is shown to
     * the partner exactly once and never returned again (the store keeps only a
     * salted hash — SEC-09 §4). The key is {@code SANDBOX}-scoped so it cannot
     * authorize real-money production calls.
     */
    @PostMapping("/{partnerId}/sandbox-keys")
    public ResponseEntity<SandboxKeyClient.IssuedSandboxKey> issueSandboxKey(
            @PathVariable String partnerId,
            @RequestBody(required = false) IssueSandboxKeyRequest body) {
        rbac.requirePartnerScope(partnerId);
        String name = body == null ? null : body.name();
        SandboxKeyClient.IssuedSandboxKey issued = sandboxKeys.issue(partnerId, name);
        return ResponseEntity.status(HttpStatus.CREATED).body(issued);
    }

    /**
     * Lists the SANDBOX keys already minted for this partner (id, prefix,
     * scope, createdAt) — never the plaintext secret. Backs the Get-Started
     * page's "you already have sandbox keys" list.
     */
    @GetMapping("/{partnerId}/sandbox-keys")
    public List<SandboxKeyClient.SandboxKeyView> sandboxKeys(@PathVariable String partnerId) {
        rbac.requirePartnerScope(partnerId);
        List<SandboxKeyClient.SandboxKeyView> keys = sandboxKeys.listForPartner(partnerId);
        return keys == null ? List.of() : keys;
    }

    /** Optional request body for {@link #issueSandboxKey}. {@code name} is a human label. */
    public record IssueSandboxKeyRequest(String name) {}

    @GetMapping("/{partnerId}/statement")
    public ResponseEntity<byte[]> statement(
            @PathVariable String partnerId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        rbac.requirePartnerScope(partnerId);
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
     * The partner-facing <b>settlement statement</b> (GAP T4-5) — read-only.
     *
     * <p>Not to be confused with {@code /{partnerId}/statement}, which is the CSV of the partner's
     * TRANSACTIONS. This is the settled record: one entry per settlement batch the partner appears on
     * over the window, each with the batch's real lifecycle status and its honest transmission state,
     * summed from the persisted settlement lines rather than recomputed from live transactions.
     *
     * <p>A partner reading this can distinguish three different things that used to be one word:
     * GMEPay+ booked the settlement, GMEPay+ reconciled it against the scheme's confirmation, and
     * GMEPay+ transmitted the instruction to the scheme. Only the first two happen today, which is why
     * {@code transmittedEntryCount} is 0 and {@code transmissionChannel.live} is false on every
     * response — stated, not implied.
     *
     * <p>Read-only by design: partner self-serve settlement <em>writes</em> (dispute, adjust, request
     * payout) are an open product decision (T1-5) and are deliberately absent rather than stubbed.
     */
    @GetMapping("/{partnerId}/settlements")
    public SettlementClient.PartnerStatement settlementStatement(
            @PathVariable String partnerId,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to,
            @RequestParam(required = false, defaultValue = "true") boolean includeLines) {
        rbac.requirePartnerScope(partnerId);
        if (from != null && to != null && to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "to must not be before from");
        }
        return settlement.statement(partnerId, from, to, includeLines);
    }

    /**
     * Synthesizes a Phase-1 {@link TransactionDetail} from the read-side summary.
     * Mirrors {@code AdminDashboardController#buildDetail} so the Portal UI sees
     * the same shape as the Admin UI.
     */
    private TransactionDetail buildDetail(TransactionMgmtClient.TransactionSummary summary) {
        ConfigRegistryClient.PartnerSummary partner = configRegistry.getPartner(summary.partnerId());
        RoundingMode mode = partner == null ? RoundingMode.HALF_UP : partner.settlementRoundingMode();
        // Real values from transaction-mgmt — the scheme ref / approval / merchant id / merchant NAME
        // (T4-4) / approvedAt are the genuine merchant-paid evidence (not "SCH-"/"AP-" placeholders).
        // Settlement booking is locked at settlement time, so booked amount + residual are null on a
        // freshly approved txn.
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
                summary.merchantName(),       // T4-4: real captured name; null = genuinely not known
                summary.statusHistory(),      // ordered status history (null-safe)
                summary.failureReason(),      // null-safe on older txns
                summary.statusLabel(),        // plain-language status label (null-safe)
                summary.declineReasonText()); // human-readable decline reason (null-safe)
    }
}
