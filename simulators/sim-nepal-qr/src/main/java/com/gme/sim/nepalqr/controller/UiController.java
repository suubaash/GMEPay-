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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Same-origin convenience endpoint for the operator console web UI served at the
 * sim's root. The UI does NOT need to build the signed Khalti envelope (base64
 * {@code data} + signature + Key/X-KhaltiNonce headers) — it POSTs a plain body
 * here and this controller runs the SAME scan-and-pay logic as
 * {@link IssuanceExtensionController#pay} and STILL records the request/response
 * into {@link NepalQrStore}, so every UI action lands in the inspection store.
 *
 *   POST /sim/nepal-qr/ui/pay  {"qs":..,"amountPaisa":..,"reference":..,
 *                               "mobile"?,"purpose"?,"remarks"?,"outcome"?}
 */
@RestController
public class UiController {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ISO_KST = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final NepalQrConfig config;
    private final NepalQrStore store;
    private final Recorder recorder;
    private final ObjectMapper mapper;

    public UiController(NepalQrConfig config, NepalQrStore store,
                        Recorder recorder, ObjectMapper mapper) {
        this.config = config;
        this.store = store;
        this.recorder = recorder;
        this.mapper = mapper;
    }

    @PostMapping(value = "/sim/nepal-qr/ui/pay", consumes = MediaType.ALL_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> uiPay(@RequestBody(required = false) String rawBody,
                                   HttpServletRequest http) {
        SimRecord rec = recorder.begin(http.getRequestURI(), http, rawBody);

        JsonNode body;
        try {
            body = rawBody == null || rawBody.isBlank() ? null : mapper.readTree(rawBody);
        } catch (Exception e) {
            body = null;
        }
        if (body == null) {
            return record(rec, HttpStatus.BAD_REQUEST, Map.of("detail", "Invalid body.", "error_key", "validation_error"));
        }
        // The console body is itself the decoded payload — expose it for inspection.
        rec.decodedPayload = mapper.convertValue(body, Object.class);

        String qs = text(body, "qs");
        String reference = text(body, "reference");
        String purpose = text(body, "purpose");
        Long amountPaisa = longVal(body, "amountPaisa");
        if (amountPaisa == null) amountPaisa = longVal(body, "amount");

        Map<String, Object> missing = new LinkedHashMap<>();
        if (amountPaisa == null || amountPaisa <= 0) missing.put("amount", new String[]{"This field is required."});
        if (reference == null || reference.isBlank()) missing.put("reference", new String[]{"This field is required."});
        if (!missing.isEmpty()) {
            missing.put("error_key", "validation_error");
            return record(rec, HttpStatus.BAD_REQUEST, missing);
        }
        if (purpose == null) purpose = "Remittance";

        rec.reference = reference;
        rec.qs = qs;
        rec.amountPaisa = amountPaisa;

        // Dedup: reference is globally unique (shared store with the signed API).
        if (store.referenceExists(reference)) {
            return record(rec, HttpStatus.BAD_REQUEST, Map.of(
                    "reference", "Duplicate reference." + reference,
                    "error_key", "validation_error"));
        }

        // Outcome (config default; overridable per-request via body.outcome).
        String outcome = text(body, "outcome");
        if (outcome == null) outcome = config.getDefaultPayOutcome();
        String state = switch (outcome.toUpperCase()) {
            case "PENDING" -> "PENDING";
            case "REJECT", "REJECTED" -> "REJECTED";
            default -> "APPROVED";
        };

        if ("REJECTED".equals(state)) {
            return record(rec, HttpStatus.BAD_REQUEST,
                    Map.of("detail", "Payment failed.", "error_key", "khalti_error"));
        }

        String idx = "KHTXN" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        TxnRecord txn = new TxnRecord();
        txn.idx = idx;
        txn.reference = reference;
        txn.amountPaisa = amountPaisa;
        txn.mobile = text(body, "mobile");
        txn.qs = qs;
        txn.purpose = purpose;
        txn.remarks = text(body, "remarks");
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
        r.put("status", state);
        r.put("meta", meta);
        return record(rec, HttpStatus.OK, r);
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
