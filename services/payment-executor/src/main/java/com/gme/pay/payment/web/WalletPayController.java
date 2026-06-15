package com.gme.pay.payment.web;

import com.gme.pay.payment.domain.GmeremitPaymentService;
import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import com.gme.pay.payment.domain.PaymentStatus;
import com.gme.pay.payment.domain.SendmnPaymentService;
import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.web.dto.WalletPaymentRequest;
import com.gme.pay.payment.web.dto.WalletPaymentResponse;
import com.gme.pay.payment.web.dto.WalletRefundRequest;
import com.gme.pay.payment.web.dto.WalletRefundResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Wallet payment entry point at {@code POST /v1/pay}.
 *
 * <p>Dispatches to the correct service based on the {@code partner} field:
 * <ul>
 *   <li>{@code partner=GMEREMIT} — domestic KRW→KRW path ({@link GmeremitPaymentService}).
 *   <li>{@code partner=SENDMN}   — overseas KRW→MNT path ({@link SendmnPaymentService}).
 *       Requires {@code amountKrw} to be present. The response adds FX fields
 *       ({@code fxApplied}, {@code fxRate}, {@code payAmountMnt}).
 * </ul>
 *
 * <p>Request:
 * <pre>
 * POST /v1/pay
 * Content-Type: application/json
 * {
 *   "qrPayload"  : "&lt;raw EMVCo QR string scanned by wallet&gt;",
 *   "amountKrw"  : "50000",
 *   "partner"    : "GMEREMIT" | "SENDMN",
 *   "userRef"    : "&lt;wallet user ID&gt;"
 * }
 * </pre>
 */
@RestController
@RequestMapping("/v1/pay")
public class WalletPayController {

    private static final Logger log = LoggerFactory.getLogger(WalletPayController.class);

    private static final String PARTNER_GMEREMIT = "GMEREMIT";
    private static final String PARTNER_SENDMN   = "SENDMN";

    /**
     * SENDMN sandbox partner ID. A real deployment would look this up from config-registry
     * but for the sandbox we use a well-known constant so no DB round-trip is needed.
     */
    private static final long SENDMN_PARTNER_ID = 2L;

    private final GmeremitPaymentService gmeremitPaymentService;
    private final SendmnPaymentService sendmnPaymentService;
    @Nullable private final SchemeClient schemeClient;
    @Nullable private final TransactionClient transactionClient;
    @Nullable private final RevenueLedgerClient revenueLedgerClient;

    /**
     * Production constructor — all collaborators injected.
     * Spring 6 two-constructor trap: @Autowired on the @Nullable-bearing ctor.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public WalletPayController(GmeremitPaymentService gmeremitPaymentService,
                               SendmnPaymentService sendmnPaymentService,
                               @Nullable SchemeClient schemeClient,
                               @Nullable TransactionClient transactionClient,
                               @Nullable RevenueLedgerClient revenueLedgerClient) {
        this.gmeremitPaymentService = gmeremitPaymentService;
        this.sendmnPaymentService = sendmnPaymentService;
        this.schemeClient = schemeClient;
        this.transactionClient = transactionClient;
        this.revenueLedgerClient = revenueLedgerClient;
    }

    /** Backwards-compatible 2-arg constructor used by existing tests. */
    WalletPayController(GmeremitPaymentService gmeremitPaymentService,
                        SendmnPaymentService sendmnPaymentService) {
        this(gmeremitPaymentService, sendmnPaymentService, null, null, null);
    }

    /**
     * POST /v1/pay — dispatches to GMEREMIT domestic or SENDMN overseas path.
     */
    @PostMapping
    public ResponseEntity<WalletPaymentResponse> pay(@RequestBody WalletPaymentRequest req) {
        req.validate();

        BigDecimal amountKrw = new BigDecimal(req.amountKrw());
        WalletResult result;

        if (PARTNER_SENDMN.equalsIgnoreCase(req.partner())) {
            result = sendmnPaymentService.pay(req.qrPayload(), amountKrw,
                    req.userRef(), SENDMN_PARTNER_ID);
        } else if (PARTNER_GMEREMIT.equalsIgnoreCase(req.partner())) {
            result = gmeremitPaymentService.pay(req.qrPayload(), amountKrw, req.userRef());
        } else {
            throw new IllegalArgumentException(
                    "Unsupported partner: " + req.partner()
                            + ". Supported: GMEREMIT, SENDMN");
        }

        WalletPaymentResponse response = new WalletPaymentResponse(
                result.approved() ? "APPROVED" : "DECLINED",
                result.schemeTxnRef(),
                result.merchantName(),
                result.payAmountKrw() != null ? result.payAmountKrw().toPlainString() : null,
                result.feeKrw() != null ? result.feeKrw().toPlainString() : null,
                result.chargedKrw() != null ? result.chargedKrw().toPlainString() : null,
                result.committedAt(),
                result.declineReason(),
                result.fxApplied(),
                result.fxRate() != null ? result.fxRate().toPlainString() : null,
                result.payAmountMnt() != null ? result.payAmountMnt().toPlainString() : null
        );

        HttpStatus status = result.approved() ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * POST /v1/pay/{schemeTxnRef}/refund — refund a wallet payment.
     *
     * <p>Calls the scheme adapter's cancel/refund path with the {@code schemeApprovalCode}
     * (authorise-level authId) that was stored at payment time. The caller MUST pass the
     * {@code schemeApprovalCode} (authId) in the request body — this is what sim-scheme's
     * {@code /payments/{authId}/refund} endpoint requires, NOT the commit-level schemeTxnRef.
     *
     * <p>Response: 200 OK with {@link WalletRefundResponse}.
     * 422 if the scheme declines the refund (already refunded, etc.).
     */
    @PostMapping("/{schemeTxnRef}/refund")
    public ResponseEntity<WalletRefundResponse> refund(
            @PathVariable("schemeTxnRef") String schemeTxnRef,
            @RequestBody(required = false) WalletRefundRequest req) {

        // The authId (schemeApprovalCode) is passed in the request body.
        // If not provided, fall back to schemeTxnRef (best-effort for cases where authId == schemeTxnRef).
        String authId = (req != null && req.authId() != null && !req.authId().isBlank())
                ? req.authId()
                : schemeTxnRef;
        String reason = (req != null && req.reason() != null) ? req.reason() : "PARTNER_REFUND";

        if (schemeClient == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new WalletRefundResponse("FAILED", schemeTxnRef, null,
                            null, "Scheme client not configured"));
        }

        try {
            schemeClient.cancelPayment(authId, reason);
        } catch (RuntimeException ex) {
            log.warn("Refund failed for schemeTxnRef={} authId={}: {}", schemeTxnRef, authId, ex.getMessage());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new WalletRefundResponse("FAILED", schemeTxnRef, null,
                            null, ex.getMessage()));
        }

        // Record the reversal in transaction-mgmt (resilient)
        if (transactionClient != null) {
            try {
                transactionClient.commitStatus(schemeTxnRef,
                        new TransactionClient.StatusPatch(
                                PaymentStatus.REVERSED, schemeTxnRef, authId, null, null));
            } catch (RuntimeException ex) {
                log.warn("transaction-mgmt REVERSED update failed for {}: {}", schemeTxnRef, ex.getMessage());
            }
        }

        // Reverse revenue-ledger entry (resilient)
        if (revenueLedgerClient != null) {
            try {
                revenueLedgerClient.postRoundingResidual(schemeTxnRef + "-REFUND",
                        java.math.BigDecimal.ZERO, "KRW");
            } catch (RuntimeException ex) {
                log.warn("revenue-ledger refund post failed for {}: {}", schemeTxnRef, ex.getMessage());
            }
        }

        return ResponseEntity.ok(new WalletRefundResponse(
                "REFUNDED",
                schemeTxnRef,
                authId,
                Instant.now().toString(),
                null
        ));
    }
}
