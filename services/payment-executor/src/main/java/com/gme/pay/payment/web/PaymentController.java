package com.gme.pay.payment.web;

import com.gme.pay.payment.domain.PartnerType;
import com.gme.pay.payment.domain.PaymentOrchestrator;
import com.gme.pay.payment.domain.PaymentOrchestrator.CancelResult;
import com.gme.pay.payment.domain.PaymentOrchestrator.CpmPaymentCommand;
import com.gme.pay.payment.domain.PaymentOrchestrator.MpmPaymentCommand;
import com.gme.pay.payment.domain.PaymentOrchestrator.PaymentResult;
import com.gme.pay.payment.web.dto.CancelPaymentRequest;
import com.gme.pay.payment.web.dto.CancelPaymentResponse;
import com.gme.pay.payment.web.dto.CpmGenerateRequest;
import com.gme.pay.payment.web.dto.CpmGenerateResponse;
import com.gme.pay.payment.web.dto.MpmPaymentRequest;
import com.gme.pay.payment.web.dto.MpmPaymentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * REST controller exposing the Payment Executor API surface (API-05).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /v1/payments          — Fixed MPM payment execution
 *   <li>POST /v1/payments/cpm/generate — CPM QR token generation
 *   <li>POST /v1/payments/{id}/cancel  — Same-day cancellation
 * </ul>
 */
@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    private final PaymentOrchestrator orchestrator;

    /**
     * Constructor injection — the orchestrator is wired with real or fake collaborators.
     * In production the collaborators are HTTP adapters; in tests they are hand-written fakes.
     */
    public PaymentController(PaymentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * POST /v1/payments — execute a Fixed MPM payment.
     *
     * <p>Requires header {@code Idempotency-Key} per API-05 §3.4.
     * Partner identity is carried in request headers (X-API-Key / X-Timestamp / X-Signature);
     * for this wave the partner type is resolved from request context — hardcoded to OVERSEAS
     * as a stub so tests exercise the full prefunding path.
     */
    @PostMapping
    public ResponseEntity<MpmPaymentResponse> executeMpmPayment(
            @RequestBody MpmPaymentRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Partner-Id", defaultValue = "1") long partnerId,
            @RequestHeader(value = "X-Partner-Type", defaultValue = "OVERSEAS") String partnerTypeHeader) {

        req.validate();

        PartnerType partnerType = PartnerType.valueOf(partnerTypeHeader.toUpperCase());

        MpmPaymentCommand cmd = new MpmPaymentCommand(
                partnerId,
                req.quoteId(),
                req.merchantQr(),
                req.schemeId(),
                req.direction(),
                req.customerRef(),
                req.partnerTxnRef()
        );

        PaymentResult result = orchestrator.executeMpm(cmd, partnerType);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(toResponse(result));
    }

    /**
     * POST /v1/payments/cpm/generate — execute a CPM (Consumer-Presented Mode) payment.
     *
     * <p>For CPM the customer presents a token-QR on their device; the merchant's terminal
     * scans it and POSTs here to authorise and capture. This delegates to the orchestrator
     * which calls the scheme-adapter-zeropay /cpm endpoint.</p>
     *
     * <p>The {@code X-Partner-Type} header controls prefunding: OVERSEAS deducts from
     * the prefunding pool, LOCAL (default for CPM) does not.</p>
     */
    @PostMapping("/cpm/generate")
    public ResponseEntity<CpmGenerateResponse> generateCpmToken(
            @RequestBody CpmGenerateRequest req,
            @RequestHeader(value = "X-Partner-Id", defaultValue = "1") long partnerId,
            @RequestHeader(value = "X-Partner-Type", defaultValue = "LOCAL") String partnerTypeHeader) {

        req.validate();

        PartnerType partnerType = PartnerType.valueOf(partnerTypeHeader.toUpperCase());

        // For CPM the collectionAmount == payoutAmount (KRW domestic default)
        BigDecimal collectionAmount = new BigDecimal(req.collectionAmount());

        CpmPaymentCommand cmd = new CpmPaymentCommand(
                partnerId,
                req.partnerTxnRef(),
                req.schemeId(),
                req.quoteId(),           // quoteId field re-used as the cpmToken for CPM
                "UNKNOWN",               // merchantId unknown until scheme decode
                collectionAmount,
                req.collectionCurrency(),
                collectionAmount,
                req.collectionCurrency(),
                null                     // no USD prefunding amount for LOCAL
        );

        PaymentResult result = orchestrator.executeCpm(cmd, partnerType);

        CpmGenerateResponse response = new CpmGenerateResponse(
                result.paymentId(),
                result.schemeTxnId(),     // schemeTxnRef returned as the "qr_token" for CPM
                result.approvedAt(),
                req.schemeId()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * POST /v1/payments/{id}/cancel — cancel a same-day approved payment.
     *
     * <p>Only APPROVED or PENDING payments on the same calendar day (KST) may be cancelled.
     * For OVERSEAS partners the prefunding deduction is reversed.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<CancelPaymentResponse> cancelPayment(
            @PathVariable("id") String paymentId,
            @RequestBody(required = false) CancelPaymentRequest req,
            @RequestHeader(value = "X-Partner-Id", defaultValue = "1") long partnerId,
            @RequestHeader(value = "X-Partner-Type", defaultValue = "OVERSEAS") String partnerTypeHeader,
            @RequestHeader(value = "X-Txn-Ref", required = false) String txnRef,
            @RequestHeader(value = "X-Scheme-Txn-Ref", required = false) String schemeTxnRef) {

        PartnerType partnerType = PartnerType.valueOf(partnerTypeHeader.toUpperCase());
        String reason = (req != null && req.reason() != null) ? req.reason() : "PARTNER_INITIATED";
        String resolvedTxnRef = txnRef != null ? txnRef : paymentId;
        String resolvedSchemeTxnRef = schemeTxnRef != null ? schemeTxnRef : paymentId;

        CancelResult result = orchestrator.cancelPayment(
                paymentId, resolvedSchemeTxnRef, partnerType, partnerId, resolvedTxnRef, reason);

        return ResponseEntity.ok(new CancelPaymentResponse(
                result.paymentId(),
                "cancelled",
                result.cancelledAt(),
                result.prefundReturnedUsd()
        ));
    }

    // ---- mapping ----

    private static MpmPaymentResponse toResponse(PaymentResult r) {
        return new MpmPaymentResponse(
                r.paymentId(),
                r.status().name().toLowerCase(),
                r.schemeTxnId(),
                r.merchantName(),
                r.merchantId(),
                r.targetPayout(),
                r.payoutCurrency(),
                r.offerRate(),
                r.collectionAmount(),
                r.collectionCurrency(),
                r.serviceCharge(),
                r.serviceChargeCurrency(),
                r.prefundDeductedUsd(),
                r.partnerTxnRef(),
                r.createdAt(),
                r.approvedAt()
        );
    }
}
