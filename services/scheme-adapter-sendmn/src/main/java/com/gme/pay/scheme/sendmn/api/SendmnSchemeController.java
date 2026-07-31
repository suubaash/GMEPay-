package com.gme.pay.scheme.sendmn.api;

import com.gme.pay.scheme.sendmn.adapter.SendmnSchemeAdapter;
import com.gme.pay.scheme.sendmn.dto.StatusResponse;
import com.gme.pay.scheme.sendmn.dto.SubmitMpmRequest;
import com.gme.pay.scheme.sendmn.dto.SubmitMpmResponse;
import com.gme.pay.scheme.sendmn.dto.VerifyQrRequest;
import com.gme.pay.scheme.sendmn.dto.VerifyQrResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal REST API exposed by the SendMN QR scheme adapter service.
 *
 * <p>Endpoints are internal-only (consumed by payment-executor, not the public gateway),
 * mirroring {@code /internal/scheme/nepal/...} so the hub's {@code RestSchemeClient}
 * pattern fits (plan decision D3).</p>
 *
 * <p><b>Shape note:</b> SendMN is two-step at the scheme edge — VerifyQr resolves the
 * merchant and mints the {@code TX_TOKEN_NO}, then Confirm ({@code /submit-mpm}) moves
 * the money. Status is poll-only (SendMN has no callback to partners).</p>
 */
@RestController
@RequestMapping("/internal/scheme/sendmn")
public class SendmnSchemeController {

    private final SendmnSchemeAdapter adapter;

    public SendmnSchemeController(SendmnSchemeAdapter adapter) {
        this.adapter = adapter;
    }

    /** POST /internal/scheme/sendmn/verify-qr — decode a scanned merchant QR, mint TX_TOKEN_NO. */
    @PostMapping("/verify-qr")
    public ResponseEntity<VerifyQrResponse> verifyQr(@RequestBody VerifyQrRequest req) {
        return ResponseEntity.ok(adapter.verifyQr(req));
    }

    /** POST /internal/scheme/sendmn/submit-mpm — execute the payment (SendMN Confirm). */
    @PostMapping("/submit-mpm")
    public ResponseEntity<SubmitMpmResponse> submitMpm(@RequestBody SubmitMpmRequest req) {
        return ResponseEntity.ok(adapter.submitMpm(req));
    }

    /** GET /internal/scheme/sendmn/status/{txTokenNo} — poll SendMN PaymentStatus. */
    @GetMapping("/status/{txTokenNo}")
    public ResponseEntity<StatusResponse> status(@PathVariable("txTokenNo") String txTokenNo) {
        return ResponseEntity.ok(adapter.status(txTokenNo));
    }

    /**
     * GET /internal/scheme/sendmn/status/by-reference/{reference} — restart-proof ADR-016
     * probe keyed by the HUB's stable partner reference (persisted at verify-qr, before
     * Confirm). Unknown reference → 404 {@code PAYMENT_NOT_FOUND}: no verify-qr committed
     * here, so no Confirm can have been sent.
     */
    @GetMapping("/status/by-reference/{reference}")
    public ResponseEntity<StatusResponse> statusByReference(
            @PathVariable("reference") String reference) {
        return ResponseEntity.ok(adapter.statusByReference(reference));
    }
}
