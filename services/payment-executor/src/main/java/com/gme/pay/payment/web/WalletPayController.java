package com.gme.pay.payment.web;

import com.gme.pay.payment.alert.DeclineSpikeMonitor;
import com.gme.pay.payment.domain.FailoverPaymentRouter;
import com.gme.pay.payment.domain.GmeremitPaymentService;
import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import com.gme.pay.payment.domain.OperationalGate;
import com.gme.pay.payment.domain.PaymentStatus;
import com.gme.pay.payment.domain.QrSchemeClassifier;
import com.gme.pay.payment.domain.QrSchemeClassifier.Classification;
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
    @Nullable private final FailoverPaymentRouter failoverPaymentRouter;
    @Nullable private final SchemeClient schemeClient;
    @Nullable private final TransactionClient transactionClient;
    @Nullable private final RevenueLedgerClient revenueLedgerClient;
    /** Operations operational gate — checked at the START of every NEW wallet payment. */
    @Nullable private final OperationalGate operationalGate;
    /** DECLINE_SPIKE monitor (defect #5) — records each outcome; null when the feature is off. */
    @Nullable private final DeclineSpikeMonitor declineSpikeMonitor;

    /**
     * Production constructor — all collaborators injected.
     * Spring 6 two-constructor trap: @Autowired on the @Nullable-bearing ctor.
     */
    @org.springframework.beans.factory.annotation.Autowired
    public WalletPayController(GmeremitPaymentService gmeremitPaymentService,
                               SendmnPaymentService sendmnPaymentService,
                               FailoverPaymentRouter failoverPaymentRouter,
                               @Nullable SchemeClient schemeClient,
                               @Nullable TransactionClient transactionClient,
                               @Nullable RevenueLedgerClient revenueLedgerClient,
                               @Nullable OperationalGate operationalGate,
                               @Nullable DeclineSpikeMonitor declineSpikeMonitor) {
        this.gmeremitPaymentService = gmeremitPaymentService;
        this.sendmnPaymentService = sendmnPaymentService;
        this.failoverPaymentRouter = failoverPaymentRouter;
        this.schemeClient = schemeClient;
        this.transactionClient = transactionClient;
        this.revenueLedgerClient = revenueLedgerClient;
        this.operationalGate = operationalGate;
        this.declineSpikeMonitor = declineSpikeMonitor;
    }

    /** Backwards-compatible 2-arg constructor used by existing tests (no failover routing). */
    WalletPayController(GmeremitPaymentService gmeremitPaymentService,
                        SendmnPaymentService sendmnPaymentService) {
        this(gmeremitPaymentService, sendmnPaymentService, null, null, null, null, null, null);
    }

    /**
     * POST /v1/pay — dispatches to GMEREMIT domestic or SENDMN overseas path.
     */
    @PostMapping
    public ResponseEntity<WalletPaymentResponse> pay(@RequestBody WalletPaymentRequest req) {
        req.validate();

        BigDecimal amountKrw = new BigDecimal(req.amountKrw());
        WalletResult result;

        // Operations operational gate: refuse NEW payments while the platform is paused / in
        // maintenance, or when THIS payment's partner alias / classified network (route) is suspended.
        // Runs at the START, before any merchant lookup / scheme submit, so nothing irreversible fires
        // on a rejected payment. Confirm/refund of an in-flight txn does not enter this controller.
        Classification gateQr = QrSchemeClassifier.classify(req.qrPayload());
        if (operationalGate != null) {
            operationalGate.checkNewAuthorization(
                    req.partner(),
                    null,
                    gateQr.isKnown() ? gateQr.networkIdentifier() : null);
        }

        // ADR-016: route the scanned MPM QR by its OWN network identifier, not by partner. A
        // non-ZeroPay network (Fonepay/NepalPay/Khalti…) arrives as partner=GMEREMIT but must NOT
        // go down the ZeroPay domestic path (it would 404 with MERCHANT_NOT_FOUND). We classify the
        // QR and, for a known non-ZeroPay network, dispatch through the FailoverPaymentRouter
        // (classify → resolve ordered candidates → failover). This subsumes the retired
        // NepalQrDetector: a Fonepay QR classifies to fonepay.com and resolves to the Nepal
        // candidate. ZeroPay QRs (com.zeropay / 5802KR) keep the unchanged GMEREMIT/SENDMN paths
        // so their merchant validation + fee behaviour is preserved exactly.
        Classification qr = gateQr;
        boolean routeViaFailover = failoverPaymentRouter != null
                && qr.isKnown()
                && !isZeroPayNetwork(qr.networkIdentifier());

        if (routeViaFailover) {
            // Non-ZeroPay networks routed via failover are cross-border (OVERSEAS) in this sandbox.
            result = failoverPaymentRouter.pay(req.qrPayload(), amountKrw, req.userRef(), "OVERSEAS");
        } else if (PARTNER_SENDMN.equalsIgnoreCase(req.partner())) {
            result = sendmnPaymentService.pay(req.qrPayload(), amountKrw,
                    req.userRef(), SENDMN_PARTNER_ID);
        } else if (PARTNER_GMEREMIT.equalsIgnoreCase(req.partner())) {
            result = gmeremitPaymentService.pay(req.qrPayload(), amountKrw, req.userRef());
        } else {
            throw new IllegalArgumentException(
                    "Unsupported partner: " + req.partner()
                            + ". Supported: GMEREMIT, SENDMN");
        }

        // DECLINE_SPIKE monitor (defect #5): record the outcome per partner + classified network so a
        // burst of declines on either dimension raises an ops alert. No-op when the feature is off.
        if (declineSpikeMonitor != null) {
            declineSpikeMonitor.record(
                    req.partner(),
                    qr.isKnown() ? qr.networkIdentifier() : null,
                    result.approved());
        }

        WalletPaymentResponse response = new WalletPaymentResponse(
                result.approved() ? "APPROVED" : "DECLINED",
                result.txnRef(),
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

    /** True when the classified QR network is ZeroPay (domestic path stays on the existing services). */
    private static boolean isZeroPayNetwork(String networkIdentifier) {
        return networkIdentifier != null
                && networkIdentifier.toLowerCase(java.util.Locale.ROOT).contains("zeropay");
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
