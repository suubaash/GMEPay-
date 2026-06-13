package com.gme.sim.scheme.controller;

import com.gme.sim.scheme.config.SchemeConfig;
import com.gme.sim.scheme.config.SchemeProfile;
import com.gme.sim.scheme.dto.*;
import com.gme.sim.scheme.emvco.CpmTokenBuilder;
import com.gme.sim.scheme.emvco.Crc16;
import com.gme.sim.scheme.emvco.EmvcoQrEncoder;
import com.gme.sim.scheme.model.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * KHQR / ZeroPay EMVCo QR scheme simulator controller.
 *
 * All timestamps are returned in KST (Asia/Seoul).
 * All money amounts are BigDecimal serialized as JSON strings via Jackson.
 */
@RestController
@RequestMapping("/v1/scheme")
public class SchemeController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ISO_KST =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(KST);

    private final SchemeConfig schemeConfig;
    private final SchemeStore  store;

    public SchemeController(SchemeConfig schemeConfig, SchemeStore store) {
        this.schemeConfig = schemeConfig;
        this.store        = store;
    }

    // -------------------------------------------------------------------------
    // Merchant registration
    // -------------------------------------------------------------------------

    @PostMapping("/merchants")
    public ResponseEntity<MerchantRecord> registerMerchant(
            @Valid @RequestBody RegisterMerchantRequest req) {
        MerchantRecord record = new MerchantRecord(
                req.merchantId(), req.name(), req.city(), req.mcc());
        store.saveMerchant(record);
        return ResponseEntity.status(HttpStatus.CREATED).body(record);
    }

    // -------------------------------------------------------------------------
    // QR generation — MPM Static
    // -------------------------------------------------------------------------

    @PostMapping("/qr/static")
    public ResponseEntity<?> generateStaticQr(
            @Valid @RequestBody StaticQrRequest req) {
        return store.findMerchant(req.merchantId())
                .map(m -> {
                    SchemeProfile profile = schemeConfig.getProfile();
                    String payload = EmvcoQrEncoder.buildStatic(m, profile);
                    String humanReadable = buildHumanReadable(m, profile, null);
                    return ResponseEntity.ok(new QrResponse("MPM_STATIC", payload, humanReadable));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .<QrResponse>build());
    }

    // -------------------------------------------------------------------------
    // QR generation — MPM Dynamic
    // -------------------------------------------------------------------------

    @PostMapping("/qr/dynamic")
    public ResponseEntity<?> generateDynamicQr(
            @Valid @RequestBody DynamicQrRequest req) {
        return store.findMerchant(req.merchantId())
                .map(m -> {
                    SchemeProfile profile = schemeConfig.getProfile();
                    String payload = EmvcoQrEncoder.buildDynamic(m, profile, req.amount());
                    String humanReadable = buildHumanReadable(m, profile, req.amount());
                    return ResponseEntity.ok(new QrResponse("MPM_DYNAMIC", payload, humanReadable));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .<QrResponse>build());
    }

    // -------------------------------------------------------------------------
    // CPM token generation
    // -------------------------------------------------------------------------

    @PostMapping("/cpm/token")
    public ResponseEntity<CpmTokenResponse> generateCpmToken(
            @Valid @RequestBody CpmTokenRequest req) {
        SchemeProfile profile = schemeConfig.getProfile();
        String aid   = profile.schemeId + "CPM";
        String token = CpmTokenBuilder.build(aid, req.customerId(), req.fundingRef());
        Instant expiresAt = Instant.now().plusSeconds(60);
        store.saveCpmToken(new CpmTokenRecord(token, req.customerId(), req.fundingRef(), expiresAt));
        return ResponseEntity.ok(new CpmTokenResponse(
                "CPM", token, ISO_KST.format(expiresAt)));
    }

    // -------------------------------------------------------------------------
    // QR decode (preview) — used by the wallet sim before it pays
    // -------------------------------------------------------------------------

    @PostMapping("/qr/decode")
    public ResponseEntity<?> decodeQr(@RequestBody java.util.Map<String, String> req) {
        String payload = req.get("qrPayload");
        if (payload == null || payload.isBlank() || !Crc16.verify(payload)) {
            return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_QR",
                    "qrPayload missing or CRC invalid");
        }
        SchemeProfile profile = schemeConfig.getProfile();
        String merchantId;
        try {
            merchantId = EmvcoQrEncoder.extractMerchantId(payload, profile);
        } catch (IllegalArgumentException e) {
            return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_QR", e.getMessage());
        }
        String init = EmvcoQrEncoder.extractInitiationMode(payload); // "11"=static, "12"=dynamic
        String mode = "12".equals(init) ? "dynamic" : "static";
        BigDecimal amount = EmvcoQrEncoder.extractAmount(payload);   // null for static
        String merchantName = store.findMerchant(merchantId)
                .map(MerchantRecord::name).orElse("Unknown");

        java.util.Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("merchantId", merchantId);
        resp.put("merchantName", merchantName);
        resp.put("mode", mode);
        resp.put("amount", amount == null ? null : amount.toPlainString());
        resp.put("currency", profile.payoutCurrency);
        return ResponseEntity.ok(resp);
    }

    // -------------------------------------------------------------------------
    // Payment authorize
    // -------------------------------------------------------------------------

    @PostMapping("/payments/authorize")
    public ResponseEntity<?> authorize(
            @Valid @RequestBody AuthorizeRequest req) {

        SchemeProfile profile = schemeConfig.getProfile();
        String merchantId;

        switch (req.mode()) {
            case "MPM_STATIC", "MPM_DYNAMIC" -> {
                if (req.qrPayload() == null || req.qrPayload().isBlank()) {
                    return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_QR",
                            "qrPayload is required for MPM modes");
                }
                // Verify CRC
                if (!Crc16.verify(req.qrPayload())) {
                    return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_QR",
                            "QR CRC verification failed");
                }
                // Extract merchant id
                try {
                    merchantId = EmvcoQrEncoder.extractMerchantId(req.qrPayload(), profile);
                } catch (IllegalArgumentException e) {
                    return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_QR", e.getMessage());
                }
                // Check merchant exists
                if (store.findMerchant(merchantId).isEmpty()) {
                    return errorResponse(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND",
                            "Merchant " + merchantId + " not found");
                }
                // For DYNAMIC: amount in QR must match request amount
                if ("MPM_DYNAMIC".equals(req.mode())) {
                    BigDecimal embeddedAmount = EmvcoQrEncoder.extractAmount(req.qrPayload());
                    if (embeddedAmount == null ||
                            embeddedAmount.compareTo(req.amount()) != 0) {
                        return errorResponse(HttpStatus.UNPROCESSABLE_ENTITY,
                                "AMOUNT_MISMATCH",
                                "Request amount " + req.amount() +
                                " does not match QR amount " + embeddedAmount);
                    }
                }
            }
            case "CPM" -> {
                if (req.cpmToken() == null || req.cpmToken().isBlank()) {
                    return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_CPM",
                            "cpmToken is required for CPM mode");
                }
                // Look up and check expiry
                CpmTokenRecord cpmRec = store.findCpmToken(req.cpmToken()).orElse(null);
                if (cpmRec == null) {
                    return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_CPM",
                            "CPM token not recognised");
                }
                if (cpmRec.isExpired()) {
                    return errorResponse(HttpStatus.CONFLICT, "TOKEN_EXPIRED",
                            "CPM token has expired");
                }
                // For CPM we don't have a merchant-id in the token;
                // a real scheme would look up by customerId. We use a sentinel.
                merchantId = "CPM_MERCHANT";
            }
            default -> {
                return errorResponse(HttpStatus.BAD_REQUEST, "INVALID_MODE",
                        "mode must be CPM | MPM_STATIC | MPM_DYNAMIC");
            }
        }

        String authId    = "AUTH-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        String schemeRef = profile.schemeId + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        Instant now      = Instant.now();

        PaymentRecord payment = new PaymentRecord(
                authId, merchantId, req.amount(), req.currency(),
                req.payerRef(), schemeRef, now);
        store.savePayment(payment);

        return ResponseEntity.ok(new AuthorizeResponse(
                authId, "APPROVED", schemeRef, merchantId,
                req.amount(), req.currency(), ISO_KST.format(now)));
    }

    // -------------------------------------------------------------------------
    // Payment commit
    // -------------------------------------------------------------------------

    @PostMapping("/payments/{authId}/commit")
    public ResponseEntity<?> commit(@PathVariable String authId) {
        PaymentRecord payment = store.findPayment(authId).orElse(null);
        if (payment == null) {
            return errorResponse(HttpStatus.NOT_FOUND, "AUTH_NOT_FOUND",
                    "No payment with authId " + authId);
        }
        try {
            Instant now = Instant.now();
            String schemeTxnRef = "TXN-" +
                    UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
            payment.capture(schemeTxnRef, now);
            return ResponseEntity.ok(new CommitResponse(
                    authId, "CAPTURED", schemeTxnRef, ISO_KST.format(now)));
        } catch (IllegalStateException e) {
            return errorResponse(HttpStatus.CONFLICT, "ILLEGAL_TRANSITION", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Payment refund
    // -------------------------------------------------------------------------

    @PostMapping("/payments/{authId}/refund")
    public ResponseEntity<?> refund(
            @PathVariable String authId,
            @Valid @RequestBody RefundRequest req) {
        PaymentRecord payment = store.findPayment(authId).orElse(null);
        if (payment == null) {
            return errorResponse(HttpStatus.NOT_FOUND, "AUTH_NOT_FOUND",
                    "No payment with authId " + authId);
        }
        try {
            String refundId = "RFD-" +
                    UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
            payment.refund(refundId, Instant.now());
            return ResponseEntity.ok(new RefundResponse(refundId, "REFUNDED"));
        } catch (IllegalStateException e) {
            return errorResponse(HttpStatus.CONFLICT, "ILLEGAL_TRANSITION", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Payment state GET
    // -------------------------------------------------------------------------

    @GetMapping("/payments/{authId}")
    public ResponseEntity<?> getPayment(@PathVariable String authId) {
        return store.findPayment(authId)
                .map(p -> ResponseEntity.ok(toStateResponse(p)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String buildHumanReadable(MerchantRecord m, SchemeProfile profile,
                                       BigDecimal amount) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(profile.schemeId).append("] ");
        sb.append(m.name()).append(" / ").append(m.city());
        if (amount != null) {
            sb.append(" | ").append(profile.payoutCurrency).append(" ").append(amount.toPlainString());
        }
        return sb.toString();
    }

    private ResponseEntity<ApiError> errorResponse(HttpStatus status, String code, String msg) {
        return ResponseEntity.status(status).body(new ApiError(code, msg));
    }

    private PaymentStateResponse toStateResponse(PaymentRecord p) {
        return new PaymentStateResponse(
                p.getAuthId(),
                p.getState().name(),
                p.getMerchantId(),
                p.getAmount(),
                p.getCurrency(),
                p.getPayerRef(),
                p.getSchemeRef(),
                ISO_KST.format(p.getAuthorizedAt()),
                p.getSchemeTxnRef(),
                p.getCommittedAt() == null ? null : ISO_KST.format(p.getCommittedAt()),
                p.getRefundId(),
                p.getRefundedAt() == null ? null : ISO_KST.format(p.getRefundedAt())
        );
    }
}
