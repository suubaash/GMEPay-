package com.gme.sim.nepalqr.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.sim.nepalqr.config.NepalQrConfig;
import com.gme.sim.nepalqr.model.NepalQrStore;
import com.gme.sim.nepalqr.model.SimRecord;
import com.gme.sim.nepalqr.model.TxnRecord;
import com.gme.sim.nepalqr.qr.QrParseResult;
import com.gme.sim.nepalqr.qr.QrParser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Khalti Issuance Extension (Scan & Pay) mock (issuance-extension.txt).
 *
 * Signed API: Authorization: Key <k> + X-KhaltiNonce:<unix ts>.
 * Body: {"data":"<base64(json)>","signature":"<base64>"}.
 * The mock base64-decodes {@code data}; signature is accepted as-is (soft-log only).
 *
 *   POST /qrscan-thirdparty/parse/   (NOT encrypted — also accepts a raw {"qs":...} body)
 *   POST /qrscan-thirdparty/pay/     (CREATES THE TXN; dedups reference)
 *   POST /qrscan-thirdparty/status/  (returns txn state by reference)
 */
@RestController
public class IssuanceExtensionController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ISO_KST = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final NepalQrConfig config;
    private final NepalQrStore store;
    private final Recorder recorder;
    private final ObjectMapper mapper;

    public IssuanceExtensionController(NepalQrConfig config, NepalQrStore store,
                                       Recorder recorder, ObjectMapper mapper) {
        this.config = config;
        this.store = store;
        this.recorder = recorder;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ parse
    @PostMapping(value = "/qrscan-thirdparty/parse/", consumes = MediaType.ALL_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> parse(@RequestBody(required = false) String rawBody,
                                   HttpServletRequest http) {
        SimRecord rec = recorder.begin(http.getRequestURI(), http, rawBody);

        // Parse is NOT encrypted: accept either a raw {"qs":...} body OR a signed envelope.
        JsonNode payload = payloadFor(rec, rawBody, /*requireAuth*/ false);
        String qs = payload == null ? null : payload.path("qs").asText(null);
        rec.qs = qs;

        if (qs == null || !QrParser.looksValid(qs)) {
            return record(rec, HttpStatus.BAD_REQUEST,
                    Map.of("detail", "Invalid QR", "error_key", "khalti_error"));
        }

        QrParseResult p = QrParser.parse(qs);
        rec.amountPaisa = p.amountPaisa;

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("format", p.format);
        r.put("initMethod", p.initMethod);
        r.put("merchantInfoExtra", p.merchantInfoExtra);
        r.put("merchantCategoryCode", p.merchantCategoryCode);
        r.put("trxCurrency", p.trxCurrency);
        r.put("trxAmount", p.amountRupees()); // rupees string, null if static
        r.put("merchantCountry", p.merchantCountry);
        r.put("merchantName", p.merchantName);
        r.put("merchantCity", p.merchantCity);
        r.put("merchantData", p.tags);
        return record(rec, HttpStatus.OK, r);
    }

    // -------------------------------------------------------------------- pay
    @PostMapping(value = "/qrscan-thirdparty/pay/", consumes = MediaType.ALL_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> pay(@RequestBody(required = false) String rawBody,
                                 HttpServletRequest http) {
        SimRecord rec = recorder.begin(http.getRequestURI(), http, rawBody);

        // Auth: Key + nonce. Invalid key -> 401.
        if (!hasKeyAuth(http)) {
            return record(rec, HttpStatus.UNAUTHORIZED, Map.of("detail", "Invalid token."));
        }

        JsonNode payload = payloadFor(rec, rawBody, /*requireAuth*/ true);
        if (payload == null) {
            return record(rec, HttpStatus.BAD_REQUEST, Map.of("detail", "Invalid encryption."));
        }

        // Field validation (empty data -> validation_error listing missing fields).
        String reference = text(payload, "reference");
        String purpose = text(payload, "purpose");
        Long amountPaisa = longVal(payload, "amount");
        String nonce = text(payload, "nonce");

        Map<String, Object> missing = new LinkedHashMap<>();
        if (amountPaisa == null) missing.put("amount", new String[]{"This field is required."});
        if (purpose == null) missing.put("purpose", new String[]{"This field is required."});
        if (nonce == null) missing.put("nonce", new String[]{"This field is required."});
        if (!missing.isEmpty()) {
            missing.put("error_key", "validation_error");
            return record(rec, HttpStatus.BAD_REQUEST, missing);
        }
        if (reference == null || reference.isBlank()) {
            return record(rec, HttpStatus.BAD_REQUEST, Map.of(
                    "reference", new String[]{"This field is required."},
                    "error_key", "validation_error"));
        }

        rec.reference = reference;
        rec.qs = text(payload, "qs");
        rec.amountPaisa = amountPaisa;

        // Nonce window check (config-toggle, off by default).
        String nonceCheck = checkNonce(http, nonce);
        if (nonceCheck != null) {
            return record(rec, HttpStatus.BAD_REQUEST,
                    Map.of("detail", nonceCheck, "error_key", "validation_error"));
        }

        // Dedup: reference is globally unique.
        if (store.referenceExists(reference)) {
            return record(rec, HttpStatus.BAD_REQUEST, Map.of(
                    "reference", "Duplicate reference." + reference,
                    "error_key", "validation_error"));
        }

        // Outcome (config default; overridable per-request via payload.outcome).
        String outcome = text(payload, "outcome");
        if (outcome == null) outcome = config.getDefaultPayOutcome();
        String state = switch (outcome.toUpperCase()) {
            case "PENDING" -> "PENDING";
            case "REJECT", "REJECTED" -> "REJECTED";
            default -> "APPROVED";
        };

        // REJECTED -> payment error (khalti_error), no txn stored.
        if ("REJECTED".equals(state)) {
            return record(rec, HttpStatus.BAD_REQUEST,
                    Map.of("detail", "Payment failed.", "error_key", "khalti_error"));
        }

        // Create + store the txn.
        String idx = "KHTXN" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        TxnRecord txn = new TxnRecord();
        txn.idx = idx;
        txn.reference = reference;
        txn.amountPaisa = amountPaisa;
        txn.mobile = text(payload, "mobile");
        txn.qs = text(payload, "qs");
        txn.purpose = purpose;
        txn.remarks = text(payload, "remarks");
        txn.state = state;
        txn.detail = "PENDING".equals(state)
                ? "Transaction is in pending state" : "Transaction has been approved";
        txn.createdAt = ISO_KST.format(ZonedDateTime.now(KST));
        store.saveTxn(txn);

        rec.idx = idx;
        rec.state = state;

        Map<String, Object> meta = new LinkedHashMap<>();
        Map<String, Object> balance = new LinkedHashMap<>();
        balance.put("primary", "1000000");
        balance.put("secondary", "0");
        balance.put("on_hold", Long.toString(amountPaisa));
        meta.put("balance", balance);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("idx", idx);
        r.put("amount", Long.toString(amountPaisa));
        r.put("type", "ScanandPay");
        r.put("detail", txn.detail);
        r.put("meta", meta);
        return record(rec, HttpStatus.OK, r);
    }

    // ----------------------------------------------------------------- status
    @PostMapping(value = "/qrscan-thirdparty/status/", consumes = MediaType.ALL_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> status(@RequestBody(required = false) String rawBody,
                                    HttpServletRequest http) {
        SimRecord rec = recorder.begin(http.getRequestURI(), http, rawBody);

        if (!hasKeyAuth(http)) {
            return record(rec, HttpStatus.UNAUTHORIZED, Map.of("detail", "Invalid token."));
        }

        JsonNode payload = payloadFor(rec, rawBody, true);
        String reference = payload == null ? null : text(payload, "reference");
        rec.reference = reference;

        TxnRecord txn = reference == null ? null : store.findTxn(reference).orElse(null);
        if (txn == null) {
            // Reference not found — HTTP 200 with state "Error".
            rec.state = "Error";
            return record(rec, HttpStatus.OK,
                    Map.of("detail", "Transaction does not exist", "state", "Error"));
        }

        rec.state = txn.state;
        String detail = switch (txn.state) {
            case "PENDING" -> "Transaction is in pending state";
            case "REJECTED" -> "Transaction has been rejected";
            case "REVERSED" -> "Transaction has been reversed/refunded";
            default -> "Transaction has been approved";
        };
        return record(rec, HttpStatus.OK, Map.of("detail", detail, "state", txn.state));
    }

    // --------------------------------------------------------------- helpers

    /** Auth is Key-based: Authorization: Key <k>. Any non-empty key accepted. */
    private boolean hasKeyAuth(HttpServletRequest http) {
        String auth = http.getHeader("Authorization");
        return auth != null && auth.startsWith("Key ") && !auth.substring(4).isBlank();
    }

    /**
     * Resolve the JSON payload from the request. Accepts EITHER a signed envelope
     * {"data":base64,"signature":...} (data is base64-decoded to JSON; signature
     * soft-logged, not verified) OR — when {@code requireAuth} is false — a raw
     * JSON body such as {"qs":...}. Sets {@link SimRecord#decodedPayload}.
     */
    private JsonNode payloadFor(SimRecord rec, String rawBody, boolean requireAuth) {
        if (rawBody == null || rawBody.isBlank()) return null;
        try {
            JsonNode body = mapper.readTree(rawBody);
            if (body.hasNonNull("data")) {
                String dataB64 = body.get("data").asText();
                byte[] decoded = Base64.getDecoder().decode(dataB64);
                JsonNode payload = mapper.readTree(new String(decoded, java.nio.charset.StandardCharsets.UTF_8));
                rec.decodedPayload = mapper.convertValue(payload, Object.class);
                return payload;
            }
            // Not an envelope: treat as raw payload (parse endpoint).
            if (!requireAuth) {
                rec.decodedPayload = mapper.convertValue(body, Object.class);
                return body;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** @return null if OK, else the error detail message. */
    private String checkNonce(HttpServletRequest http, String bodyNonce) {
        if (!config.isNonceWindowEnabled()) return null;
        String headerNonce = http.getHeader("X-KhaltiNonce");
        if (headerNonce == null || bodyNonce == null || !headerNonce.equals(bodyNonce)) {
            return "Nonce value not matched.";
        }
        try {
            long nonce = Long.parseLong(headerNonce);
            long serverTs = ZonedDateTime.now(KST).toEpochSecond();
            if (nonce < serverTs - 100 || nonce > serverTs + 200) {
                return "Nonce already expired.";
            }
        } catch (NumberFormatException e) {
            return "Nonce value not matched.";
        }
        return null;
    }

    private static String text(JsonNode n, String field) {
        return n != null && n.hasNonNull(field) && !n.get(field).asText().isEmpty()
                ? n.get(field).asText() : null;
    }

    private static Long longVal(JsonNode n, String field) {
        if (n == null || !n.hasNonNull(field)) return null;
        try {
            String s = n.get(field).asText();
            return s.isBlank() ? null : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private ResponseEntity<?> record(SimRecord rec, HttpStatus status, Object body) {
        recorder.save(rec, status.value(), body);
        return ResponseEntity.status(status).body(body);
    }
}
