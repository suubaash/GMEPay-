package com.gme.sim.nepalqr.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.sim.nepalqr.config.NepalQrConfig;
import com.gme.sim.nepalqr.dto.ValidateError;
import com.gme.sim.nepalqr.model.SimRecord;
import com.gme.sim.nepalqr.qr.QrParseResult;
import com.gme.sim.nepalqr.qr.QrParser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * QR Validate API mock (validate.txt).
 *
 * POST /api/qr/validate/  (+ aliases /api/v2/qr/validate/)
 * Auth: Authorization: Token <t>  (any non-empty token accepted; IP allowlist off by default).
 * Body: {"qr":"<string>"}
 * Returns a flat JSON keyed by network. Read-only / idempotent.
 */
@RestController
public class ValidateController {

    private final NepalQrConfig config;
    private final Recorder recorder;
    private final ObjectMapper mapper;

    public ValidateController(NepalQrConfig config, Recorder recorder, ObjectMapper mapper) {
        this.config = config;
        this.recorder = recorder;
        this.mapper = mapper;
    }

    @PostMapping(value = {"/api/qr/validate/", "/api/v2/qr/validate/", "/api/v1/qr/validate/"},
            consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> validate(@RequestBody(required = false) String rawBody,
                                      HttpServletRequest http) {
        SimRecord rec = recorder.begin(http.getRequestURI(), http, rawBody);

        // --- Auth: Authorization: Token <t> (any non-empty token) ---
        String auth = http.getHeader("Authorization");
        if (auth == null || auth.isBlank()) {
            return record(rec, HttpStatus.FORBIDDEN,
                    Map.of("detail", "You do not have permission to perform this action."));
        }
        if (!auth.startsWith("Token ") || auth.substring(6).isBlank()) {
            return record(rec, HttpStatus.UNAUTHORIZED, Map.of("detail", "Invalid token."));
        }
        // --- IP allowlist (off by default) ---
        if (config.isIpAllowlistEnabled()
                && !config.getAllowedIps().contains(http.getRemoteAddr())) {
            return record(rec, HttpStatus.UNAUTHORIZED, Map.of("detail", "Invalid IP address."));
        }

        // --- Parse body {"qr":"..."} ---
        String qr = null;
        try {
            if (rawBody != null && !rawBody.isBlank()) {
                JsonNode node = mapper.readTree(rawBody);
                if (node.hasNonNull("qr")) qr = node.get("qr").asText();
            }
        } catch (Exception ignored) { /* handled below */ }

        if (qr == null || qr.trim().length() < 8 || qr.trim().length() > 2000) {
            return record(rec, HttpStatus.BAD_REQUEST,
                    ValidateError.of("invalid_qr", "QR failed schema validation."));
        }
        qr = qr.trim();
        rec.qs = qr;

        if (!QrParser.looksValid(qr)) {
            return record(rec, HttpStatus.BAD_REQUEST,
                    ValidateError.of("unsupported_qr", "QR does not match any supported network."));
        }

        // --- JSON networks: khalti / mobank ---
        if (qr.startsWith("{")) {
            try {
                JsonNode j = mapper.readTree(qr);
                String net = j.path("network").asText("");
                if ("khalti".equalsIgnoreCase(net)) {
                    return record(rec, HttpStatus.OK, khaltiResponse(j));
                }
                if ("mobank".equalsIgnoreCase(net) || j.has("account_number")) {
                    return record(rec, HttpStatus.OK, mobankResponse(j));
                }
                return record(rec, HttpStatus.BAD_REQUEST,
                        ValidateError.of("unsupported_qr", "QR does not match any supported network."));
            } catch (Exception e) {
                return record(rec, HttpStatus.BAD_REQUEST,
                        ValidateError.of("invalid_qr", "QR failed schema validation."));
            }
        }

        // --- EMVCo merchant networks (fonepay/nepalpay/unionpay/smartqr) ---
        QrParseResult p = QrParser.parse(qr);
        rec.amountPaisa = p.amountPaisa;
        return record(rec, HttpStatus.OK, merchantResponse(p));
    }

    // --- Response builders (flat JSON keyed by network) ---

    private Map<String, Object> khaltiResponse(JsonNode j) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("network", "khalti");
        r.put("name", j.path("name").asText("Khalti User"));
        r.put("mobile", j.path("mobile").asText(j.path("Khalti_ID").asText("98XXXXXXXX")));
        return r;
    }

    private Map<String, Object> mobankResponse(JsonNode j) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("network", "mobank");
        r.put("name", j.path("name").asText("Account Holder"));
        r.put("account_number", j.path("account_number").asText("00000000000"));
        Map<String, Object> bank = new LinkedHashMap<>();
        bank.put("swift_code", j.path("bank").path("swift_code").asText("NICENPKA"));
        bank.put("name", j.path("bank").path("name").asText("NIC Asia Bank"));
        r.put("bank", bank);
        return r;
    }

    private Map<String, Object> merchantResponse(QrParseResult p) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("network", p.network);
        r.put("name", p.merchantName);
        r.put("merchant_id", p.merchantId);
        r.put("amount", p.amountPaisa); // integer paisa when dynamic, null when static
        r.put("currency", "NPR");
        r.put("purpose", p.purpose);

        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("merchant_city", p.merchantCity);
        extra.put("mcc", p.merchantCategoryCode);
        switch (p.network) {
            case "fonepay" -> extra.put("country", p.merchantCountry);
            case "nepalpay" -> {
                extra.put("is_smartqr", false);
                extra.put("is_personal_qr", false);
            }
            case "unionpay" -> {
                extra.put("iin", "62");
                extra.put("uid", p.merchantId);
            }
            case "smartqr" -> { /* only merchant_city + mcc */ }
            default -> extra.put("country", p.merchantCountry);
        }
        r.put("extra", extra);
        return r;
    }

    private ResponseEntity<?> record(SimRecord rec, HttpStatus status, Object body) {
        recorder.save(rec, status.value(), body);
        return ResponseEntity.status(status).body(body);
    }
}
