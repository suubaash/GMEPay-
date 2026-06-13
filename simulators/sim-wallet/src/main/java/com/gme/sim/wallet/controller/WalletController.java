package com.gme.sim.wallet.controller;

import com.gme.sim.wallet.model.MpmPreview;
import com.gme.sim.wallet.model.PartnerProfile;
import com.gme.sim.wallet.model.Receipt;
import com.gme.sim.wallet.service.WalletService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Wallet API endpoints.
 *
 * POST /v1/wallet/cpm/generate   — customer requests a CPM token to present at terminal
 * POST /v1/wallet/mpm/scan       — customer scanned a merchant MPM QR; decode preview
 * POST /v1/wallet/pay            — execute payment (CPM or MPM, GMEREMIT or SENDMN)
 * GET  /v1/wallet/receipts/{id}  — retrieve a stored receipt
 */
@RestController
@RequestMapping("/v1/wallet")
public class WalletController {

    private final WalletService walletService;

    @Autowired
    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    // ------------------------------------------------------------------
    // CPM generate
    // ------------------------------------------------------------------

    /**
     * Body: { partner?, customerId, amount?, currency? }
     */
    @PostMapping("/cpm/generate")
    public ResponseEntity<Map<String, String>> generateCpm(@RequestBody Map<String, String> body) {
        PartnerProfile partner = walletService.resolvePartner(body.get("partner"));
        String customerId = body.get("customerId");
        if (customerId == null || customerId.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "customerId is required"));
        }
        BigDecimal amount = body.get("amount") != null && !body.get("amount").isBlank()
                ? new BigDecimal(body.get("amount")) : null;
        String currency = body.get("currency");

        String token = walletService.generateCpmToken(partner, customerId, amount, currency);
        return ResponseEntity.ok(Map.of(
                "partner", partner.name(),
                "customerId", customerId,
                "cpmToken", token
        ));
    }

    // ------------------------------------------------------------------
    // MPM scan
    // ------------------------------------------------------------------

    /**
     * Body: { partner?, qrPayload }
     */
    @PostMapping("/mpm/scan")
    public ResponseEntity<?> scanMpm(@RequestBody Map<String, String> body) {
        PartnerProfile partner = walletService.resolvePartner(body.get("partner"));
        String qrPayload = body.get("qrPayload");
        if (qrPayload == null || qrPayload.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "qrPayload is required"));
        }
        MpmPreview preview = walletService.scanMpm(partner, qrPayload);
        return ResponseEntity.ok(preview);
    }

    // ------------------------------------------------------------------
    // Pay
    // ------------------------------------------------------------------

    /**
     * Body: { partner?, mode, qrPayload|cpmToken (optional ref), payAmountKrw }
     */
    @PostMapping("/pay")
    public ResponseEntity<?> pay(@RequestBody Map<String, String> body) {
        PartnerProfile partner = walletService.resolvePartner(body.get("partner"));

        String mode = body.get("mode");
        if (mode == null || mode.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "mode is required (mpm or cpm)"));
        }

        String payAmountStr = body.get("payAmountKrw");
        if (payAmountStr == null || payAmountStr.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "payAmountKrw is required"));
        }
        BigDecimal payAmountKrw;
        try {
            payAmountKrw = new BigDecimal(payAmountStr);
        } catch (NumberFormatException ex) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "payAmountKrw must be a valid decimal number"));
        }

        // Accept either qrPayload or cpmToken as the reference passed to the scheme
        String ref = body.get("qrPayload");
        if (ref == null || ref.isBlank()) ref = body.get("cpmToken");

        Receipt receipt = walletService.pay(partner, mode, ref, payAmountKrw);
        return ResponseEntity.ok(receipt);
    }

    // ------------------------------------------------------------------
    // Receipt lookup
    // ------------------------------------------------------------------

    @GetMapping("/receipts/{id}")
    public ResponseEntity<?> getReceipt(@PathVariable String id) {
        Receipt r = walletService.getReceipt(id);
        if (r == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(r);
    }
}
