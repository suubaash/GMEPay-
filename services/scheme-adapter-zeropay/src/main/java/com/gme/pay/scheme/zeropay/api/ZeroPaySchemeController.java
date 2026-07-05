package com.gme.pay.scheme.zeropay.api;

import com.gme.pay.scheme.zeropay.adapter.SchemeAdapter;
import com.gme.pay.scheme.zeropay.adapter.model.AdapterHealth;
import com.gme.pay.scheme.zeropay.adapter.model.CpmAuthRequest;
import com.gme.pay.scheme.zeropay.adapter.model.CpmAuthResponse;
import com.gme.pay.scheme.zeropay.adapter.model.MpmSubmitRequest;
import com.gme.pay.scheme.zeropay.adapter.model.MpmSubmitResponse;
import com.gme.pay.scheme.zeropay.dto.AdapterHealthResponse;
import com.gme.pay.scheme.zeropay.dto.CpmSubmitRequestDto;
import com.gme.pay.scheme.zeropay.dto.SubmitPaymentRequest;
import com.gme.pay.scheme.zeropay.dto.SubmitPaymentResponse;
import com.gme.pay.scheme.zeropay.persistence.GmeSchemeBalanceEntity;
import com.gme.pay.scheme.zeropay.prefund.GmeSchemeFloatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Internal REST API exposed by the ZeroPay scheme adapter service.
 *
 * <p>Endpoints are internal-only (not exposed through the public API gateway).</p>
 */
@RestController
@RequestMapping("/internal/scheme/zeropay")
public class ZeroPaySchemeController {

    private static final Logger log = LoggerFactory.getLogger(ZeroPaySchemeController.class);

    private final SchemeAdapter schemeAdapter;
    private final GmeSchemeFloatService floatService;

    public ZeroPaySchemeController(SchemeAdapter schemeAdapter, GmeSchemeFloatService floatService) {
        this.schemeAdapter = schemeAdapter;
        this.floatService = floatService;
    }

    /**
     * Pre-submit balance inquiry (SETTLEMENT_FLOW_SPEC §7.2): does GME hold enough prepaid balance
     * WITH the scheme to fund {@code amountKrw}? POST /internal/scheme/zeropay/balance-check.
     *
     * <p>Reads GME's REAL running float (seeded from an opening balance, credited on top-up, debited
     * on every committed payout) — so a drained float declines the payout at AUTHORIZE, before the
     * customer is charged. The real KFTC 전문 balance inquiry replaces this local ledger in Step 8.
     */
    @PostMapping("/balance-check")
    public ResponseEntity<BalanceCheckResponse> balanceCheck(@RequestBody BalanceCheckRequest req) {
        GmeSchemeFloatService.BalanceCheck result = floatService.check(req.amountKrw());
        return ResponseEntity.ok(new BalanceCheckResponse(result.allowed(), result.available()));
    }

    /** GET /internal/scheme/zeropay/balance — how much prepaid float GME currently holds with ZeroPay. */
    @GetMapping("/balance")
    public ResponseEntity<BalanceResponse> balance() {
        GmeSchemeBalanceEntity bal = floatService.currentBalance();
        List<BalanceEntry> entries = floatService.recentEntries().stream()
                .map(e -> new BalanceEntry(e.getEntryType(), e.getTxnRef(), e.getAmount(),
                        e.getBalanceAfter(), e.getCreatedAt()))
                .toList();
        return ResponseEntity.ok(new BalanceResponse(
                bal.getSchemeCode(), bal.getCurrency(), bal.getBalance(), bal.getUpdatedAt(), entries));
    }

    /**
     * POST /internal/scheme/zeropay/balance/topup — credit GME's prepaid float (e.g. a deposit to the
     * scheme). Idempotent on {@code reference}. Returns the new running balance.
     */
    @PostMapping("/balance/topup")
    public ResponseEntity<TopUpResponse> topUp(@RequestBody TopUpRequest req) {
        BigDecimal newBalance = floatService.credit(req.reference(), req.amountKrw());
        return ResponseEntity.ok(new TopUpResponse(floatService.schemeCode(), newBalance, floatService.currency()));
    }

    public record BalanceCheckRequest(String schemeId, BigDecimal amountKrw, String currency) {}

    public record BalanceCheckResponse(boolean allowed, BigDecimal available) {}

    public record BalanceResponse(String schemeCode, String currency, BigDecimal balance,
                                  Instant updatedAt, List<BalanceEntry> recentEntries) {}

    public record BalanceEntry(String entryType, String txnRef, BigDecimal amount,
                               BigDecimal balanceAfter, Instant at) {}

    public record TopUpRequest(String reference, BigDecimal amountKrw) {}

    public record TopUpResponse(String schemeCode, BigDecimal balance, String currency) {}

    /**
     * Submits a payment to ZeroPay (MPM mode).
     *
     * <p>POST /internal/scheme/zeropay/submit</p>
     */
    @PostMapping("/submit")
    public ResponseEntity<SubmitPaymentResponse> submit(
            @RequestBody SubmitPaymentRequest request) {

        MpmSubmitRequest domainRequest = new MpmSubmitRequest(
                request.merchantId(),
                request.amountKrw(),
                request.currency(),
                request.partnerTxnRef(),
                request.idempotencyKey(),
                request.qrPayload()
        );

        MpmSubmitResponse domainResponse = schemeAdapter.submitMpm(domainRequest);

        // Parse committedAt string to Instant (may be null for non-sim adapters)
        Instant approvedAt = null;
        if (domainResponse.committedAt() != null) {
            try {
                approvedAt = Instant.parse(domainResponse.committedAt());
            } catch (Exception ignored) {
                approvedAt = Instant.now();
            }
        }
        if (approvedAt == null) {
            approvedAt = Instant.now();
        }

        SubmitPaymentResponse response = new SubmitPaymentResponse(
                domainResponse.zeroPayTxnRef(),   // schemeTxnRef
                domainResponse.authId(),           // schemeApprovalCode
                approvedAt,                        // approvedAt
                domainResponse.zeroPayTxnRef(),    // zeroPayTxnRef (legacy)
                domainResponse.resultCode(),
                domainResponse.resultMessage(),
                "00".equals(domainResponse.resultCode())
        );

        // Committed payout → debit GME's prepaid float. Idempotent on the partner txn ref so a
        // retried submit never double-debits.
        if (response.success()) {
            String ref = request.partnerTxnRef() != null ? request.partnerTxnRef() : request.idempotencyKey();
            debitFloat(ref, request.amountKrw());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Submits a CPM (Consumer-Presented Mode) payment to ZeroPay.
     *
     * <p>POST /internal/scheme/zeropay/cpm</p>
     *
     * <p>The {@code qrToken} in the request is the CPM token previously issued via
     * {@code prepareCPM} / sim-scheme {@code /cpm/token}. This endpoint authorises and commits
     * in one step (same two-step flow as {@link #authoriseCpm}).</p>
     */
    @PostMapping("/cpm")
    public ResponseEntity<SubmitPaymentResponse> submitCpm(
            @RequestBody CpmSubmitRequestDto req) {

        // Map the wire DTO to the domain request.
        // qrToken (CPM token) is passed as qrCodeId; payoutAmount is the KRW amount.
        CpmAuthRequest domainRequest = new CpmAuthRequest(
                null,              // merchantId not needed — scheme derives from cpmToken
                req.qrToken(),     // cpmToken passed here as qrCodeId per authoriseCpm contract
                req.payoutAmount(),
                req.txnRef(),
                null
        );

        CpmAuthResponse domainResponse = schemeAdapter.authoriseCpm(domainRequest);

        // Build the standard SubmitPaymentResponse — field names match RestSchemeClient.SchemeApprovalResponse
        SubmitPaymentResponse response = new SubmitPaymentResponse(
                domainResponse.zeroPayTxnRef(),    // schemeTxnRef
                domainResponse.approvalCode(),      // schemeApprovalCode (authId)
                Instant.now(),                      // approvedAt (no committedAt from CpmAuthResponse)
                domainResponse.zeroPayTxnRef(),     // zeroPayTxnRef (legacy)
                domainResponse.resultCode(),
                domainResponse.resultMessage(),
                "00".equals(domainResponse.resultCode())
        );

        if (response.success()) {
            debitFloat(req.txnRef(), req.payoutAmount());
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Debit GME's prepaid float for a committed payout. The payout has already occurred at the scheme,
     * so a local ledger hiccup must not fail the response — it is logged, not thrown.
     */
    private void debitFloat(String reference, BigDecimal amountKrw) {
        try {
            floatService.debit(reference, amountKrw);
        } catch (RuntimeException ex) {
            log.error("failed to debit ZeroPay prepaid float for committed payout ref={} amount={}: {}",
                    reference, amountKrw, ex.getMessage(), ex);
        }
    }

    /**
     * Cancels/refunds a previously committed payment.
     *
     * <p>POST /internal/scheme/zeropay/cancel</p>
     *
     * <p>The {@code schemeTxnRef} field MUST carry the authorise-level {@code authId}
     * (stored as {@code schemeApprovalCode} in payment-executor). The sim-scheme
     * {@code /payments/{authId}/refund} endpoint requires the original authId, not the
     * commit-level schemeTxnRef.</p>
     */
    @PostMapping("/cancel")
    public ResponseEntity<Void> cancel(@RequestBody CancelRequest req) {
        schemeAdapter.cancelPayment(req.schemeTxnRef());
        return ResponseEntity.noContent().build();
    }

    /**
     * Wire DTO for the cancel endpoint.
     * Field names MUST match {@code RestSchemeClient.SchemeCancelRequest}: {@code schemeTxnRef}, {@code reason}.
     */
    record CancelRequest(String schemeTxnRef, String reason) {}

    /**
     * Returns the current health of the ZeroPay adapter.
     *
     * <p>GET /internal/scheme/zeropay/health</p>
     */
    @GetMapping("/health")
    public ResponseEntity<AdapterHealthResponse> health() {
        AdapterHealth health = schemeAdapter.healthCheck();
        AdapterHealthResponse response = new AdapterHealthResponse(
                health.status().name(),
                health.lastCheckedAt(),
                health.sftpReachable(),
                health.realtimeApiReachable(),
                health.lastError()
        );
        return ResponseEntity.ok(response);
    }
}
