package com.gme.pay.payment.web;

import com.gme.pay.payment.domain.GmeremitPaymentService;
import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import com.gme.pay.payment.web.dto.WalletPaymentRequest;
import com.gme.pay.payment.web.dto.WalletPaymentResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * Exposes the GMERemit wallet payment entry point at {@code POST /v1/pay}.
 *
 * <p>This is a dedicated top-level controller so the canonical wallet URL is
 * {@code /v1/pay} rather than the internal {@code /v1/payments/pay}. Both paths
 * are effectively equivalent — the wallet MUST use {@code /v1/pay}.
 *
 * <p>Request:
 * <pre>
 * POST /v1/pay
 * Content-Type: application/json
 * {
 *   "qrPayload"  : "&lt;raw EMVCo QR string scanned by wallet&gt;",
 *   "amountKrw"  : "50000",
 *   "partner"    : "GMEREMIT",
 *   "userRef"    : "&lt;wallet user ID&gt;"
 * }
 * </pre>
 *
 * <p>Response (APPROVED):
 * <pre>
 * HTTP 201 Created
 * {
 *   "status"       : "APPROVED",
 *   "schemeTxnRef" : "TXN-AABB...",
 *   "merchantName" : "Coffee Shop",
 *   "payAmountKrw" : "50000",
 *   "feeKrw"       : "500",
 *   "chargedKrw"   : "50500",
 *   "committedAt"  : "2026-06-13T11:23:45+09:00"
 * }
 * </pre>
 *
 * <p>Response (DECLINED):
 * <pre>
 * HTTP 422 Unprocessable Entity
 * {
 *   "status"        : "DECLINED",
 *   "merchantName"  : "Coffee Shop",
 *   "declineReason" : "MERCHANT_INACTIVE"
 * }
 * </pre>
 */
@RestController
@RequestMapping("/v1/pay")
public class WalletPayController {

    private final GmeremitPaymentService gmeremitPaymentService;

    public WalletPayController(GmeremitPaymentService gmeremitPaymentService) {
        this.gmeremitPaymentService = gmeremitPaymentService;
    }

    /**
     * POST /v1/pay — execute a GMERemit domestic (KRW→KRW) payment.
     */
    @PostMapping
    public ResponseEntity<WalletPaymentResponse> pay(@RequestBody WalletPaymentRequest req) {
        req.validate();

        BigDecimal amountKrw = new BigDecimal(req.amountKrw());
        WalletResult result = gmeremitPaymentService.pay(req.qrPayload(), amountKrw, req.userRef());

        WalletPaymentResponse response = new WalletPaymentResponse(
                result.approved() ? "APPROVED" : "DECLINED",
                result.schemeTxnRef(),
                result.merchantName(),
                result.payAmountKrw() != null ? result.payAmountKrw().toPlainString() : null,
                result.feeKrw() != null ? result.feeKrw().toPlainString() : null,
                result.chargedKrw() != null ? result.chargedKrw().toPlainString() : null,
                result.committedAt(),
                result.declineReason()
        );

        HttpStatus status = result.approved() ? HttpStatus.CREATED : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(response);
    }
}
