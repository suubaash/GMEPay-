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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Internal REST API exposed by the ZeroPay scheme adapter service.
 *
 * <p>Endpoints are internal-only (not exposed through the public API gateway).</p>
 */
@RestController
@RequestMapping("/internal/scheme/zeropay")
public class ZeroPaySchemeController {

    private final SchemeAdapter schemeAdapter;

    /**
     * SIM stand-in for GME's prepaid balance held WITH the scheme, used by the pre-submit
     * balance-check until the real 전문 balance inquiry lands with the TCP transport (Step 8).
     */
    @Value("${gmepay.scheme.zeropay.sim-prepaid-balance-krw:1000000000}")
    private long simPrepaidBalanceKrw = 1_000_000_000L;

    public ZeroPaySchemeController(SchemeAdapter schemeAdapter) {
        this.schemeAdapter = schemeAdapter;
    }

    /**
     * Pre-submit balance inquiry (SETTLEMENT_FLOW_SPEC §7.2): does GME hold enough prepaid balance
     * with the scheme to fund {@code amountKrw}? POST /internal/scheme/zeropay/balance-check.
     *
     * <p>SIM: compares against a configurable balance. The real KFTC 전문 balance inquiry replaces
     * this in Step 8.
     */
    @PostMapping("/balance-check")
    public ResponseEntity<BalanceCheckResponse> balanceCheck(@RequestBody BalanceCheckRequest req) {
        BigDecimal amount = req.amountKrw() == null ? BigDecimal.ZERO : req.amountKrw();
        BigDecimal available = BigDecimal.valueOf(simPrepaidBalanceKrw);
        boolean allowed = amount.compareTo(available) <= 0;
        return ResponseEntity.ok(new BalanceCheckResponse(allowed, available));
    }

    public record BalanceCheckRequest(String schemeId, BigDecimal amountKrw, String currency) {}

    public record BalanceCheckResponse(boolean allowed, BigDecimal available) {}

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

        return ResponseEntity.ok(response);
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
