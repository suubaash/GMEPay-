package com.gme.sim.merchant.controller;

import com.gme.sim.merchant.dto.ZeroPayChargeRequest;
import com.gme.sim.merchant.dto.ZeroPayStaticResultRequest;
import com.gme.sim.merchant.model.ShopRecord;
import com.gme.sim.merchant.model.ShopStore;
import com.gme.sim.merchant.scheme.MerchantView;
import com.gme.sim.merchant.scheme.QrView;
import com.gme.sim.merchant.scheme.SchemeWireCodec;
import com.gme.sim.merchant.scheme.WireMessage;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spec-faithful ZeroPay terminal endpoints (the "전문 / wire" side of the simulator).
 *
 * <p>Additive to {@link MerchantController} and fully local — these endpoints build the
 * ZeroPay EMVCo QR and the KFTC 전문 (420000/500000) in-process via {@link SchemeWireCodec},
 * so they work without sim-scheme. A shop must already be registered in this terminal.
 */
@RestController
@RequestMapping("/v1/merchant/zeropay")
public class ZeroPayTerminalController {

    private final ShopStore shopStore;
    private final SchemeWireCodec codec;

    /** GMEPay+'s KFTC 결제사업자 code (전문 field 23). */
    private final String requestingOrg;
    /** Default 등록기관ID used when a shop has no usable KFTC institution code. */
    private final String defaultRegistrarId;

    private final AtomicLong traceSeq = new AtomicLong(1);
    private final AtomicLong txnSeq = new AtomicLong(1);
    private final AtomicLong approvalSeq = new AtomicLong(1);

    public ZeroPayTerminalController(
            ShopStore shopStore,
            SchemeWireCodec codec,
            @Value("${gmepay.sim.merchant.zeropay.requesting-org:700}") String requestingOrg,
            @Value("${gmepay.sim.merchant.zeropay.default-registrar-id:01}") String defaultRegistrarId) {
        this.shopStore = shopStore;
        this.codec = codec;
        this.requestingOrg = requestingOrg;
        this.defaultRegistrarId = defaultRegistrarId;
    }

    // -------------------------------------------------------------------------
    // GET /v1/merchant/zeropay/{merchantId}/static-qr  — store QR (QR구분 1)
    // -------------------------------------------------------------------------
    @GetMapping("/{merchantId}/static-qr")
    public ResponseEntity<?> staticQr(@PathVariable String merchantId) {
        Optional<ShopRecord> shop = shopStore.find(merchantId);
        if (shop.isEmpty()) return shopNotFound(merchantId);
        QrView qr = codec.buildStaticQr(view(shop.get()));
        return ResponseEntity.ok(qr);
    }

    // -------------------------------------------------------------------------
    // POST /v1/merchant/zeropay/{merchantId}/dynamic-qr  — charge (QR구분 2) + 420000 wire
    // -------------------------------------------------------------------------
    @PostMapping("/{merchantId}/dynamic-qr")
    public ResponseEntity<?> dynamicCharge(@PathVariable String merchantId,
                                           @Valid @RequestBody ZeroPayChargeRequest req) {
        Optional<ShopRecord> shop = shopStore.find(merchantId);
        if (shop.isEmpty()) return shopNotFound(merchantId);

        long amount;
        try {
            amount = wholeWon(req.amount());
        } catch (IllegalArgumentException e) {
            return badRequest("INVALID_AMOUNT", e.getMessage());
        }

        SchemeWireCodec.DynamicCharge charge = codec.buildDynamicCharge(
                view(shop.get()), amount, nextTxnRef(), nextTrace());
        return ResponseEntity.ok(charge);
    }

    // -------------------------------------------------------------------------
    // POST /v1/merchant/zeropay/{merchantId}/static-result  — register 500000
    // -------------------------------------------------------------------------
    @PostMapping("/{merchantId}/static-result")
    public ResponseEntity<?> staticResult(@PathVariable String merchantId,
                                          @Valid @RequestBody ZeroPayStaticResultRequest req) {
        Optional<ShopRecord> shop = shopStore.find(merchantId);
        if (shop.isEmpty()) return shopNotFound(merchantId);

        long amount;
        try {
            amount = wholeWon(req.amount());
        } catch (IllegalArgumentException e) {
            return badRequest("INVALID_AMOUNT", e.getMessage());
        }

        String approvalNo = (req.approvalNo() != null && !req.approvalNo().isBlank())
                ? req.approvalNo().trim() : nextApproval();
        WireMessage wire = codec.buildStaticResult(
                view(shop.get()), amount, approvalNo, nextTxnRef(), nextTrace());
        return ResponseEntity.ok(wire);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private MerchantView view(ShopRecord s) {
        return new MerchantView(
                s.merchantId(), s.name(), s.city(), s.mcc(),
                resolveRegistrarId(s), terminalNo(s), requestingOrg);
    }

    /** 2-char 등록기관ID: the first two digits of the KFTC institution code, else the default. */
    private String resolveRegistrarId(ShopRecord s) {
        String code = s.kftcInstitutionCode();
        if (code != null) {
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < code.length() && digits.length() < 2; i++) {
                char c = code.charAt(i);
                if (c >= '0' && c <= '9') digits.append(c);
            }
            if (digits.length() == 2) return digits.toString();
        }
        return defaultRegistrarId;
    }

    private static String terminalNo(ShopRecord s) {
        String id = s.merchantId() == null ? "TERM" : s.merchantId();
        String t = "T" + id.replace("-", "");
        return t.length() > 20 ? t.substring(0, 20) : t;
    }

    /** KRW is a whole-won currency; reject fractional amounts. Returns the won as a long. */
    private static long wholeWon(BigDecimal amount) {
        if (amount.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("KRW amount must be a whole number of won");
        }
        return amount.longValueExact();
    }

    private String nextTrace() {
        long n = traceSeq.getAndIncrement() % 100_000_000L;
        return String.format("%08d", n);
    }

    private String nextTxnRef() {
        return "P" + String.format("%012d", txnSeq.getAndIncrement() % 1_000_000_000_000L);
    }

    private String nextApproval() {
        return "A" + String.format("%011d", approvalSeq.getAndIncrement() % 100_000_000_000L);
    }

    private ResponseEntity<Map<String, Object>> shopNotFound(String merchantId) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "code", "SHOP_NOT_FOUND",
                "message", "Shop " + merchantId + " not registered in this terminal"));
    }

    private ResponseEntity<Map<String, Object>> badRequest(String code, String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "code", code, "message", message));
    }
}
