package com.gme.sim.ninepay.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.gme.sim.ninepay.config.NinepaySimConfig;
import com.gme.sim.ninepay.lifecycle.TransferLifecycle;
import com.gme.sim.ninepay.model.NinepayStore;
import com.gme.sim.ninepay.model.Scenario;
import com.gme.sim.ninepay.model.TransferRecord;
import com.gme.sim.ninepay.sign.SimSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The 9Pay-side API surface (integration spec ver 3.13). All endpoints answer the 9Pay
 * envelope {@code {success, data}} / {@code {success:false, error:{code,message}}} with
 * HTTP 200 — the spec defines only body-level success/error semantics (open issue O7),
 * and the adapter's {@code unwrap} handles exactly that.
 *
 * <p>Every response {@code data} carries a {@code signature} produced with the SIM'S RSA
 * key over the documented per-endpoint pipe-string, so the adapter can run with
 * {@code verify-responses: true} against this sim (trust anchor: {@code GET /sim/public-key}).</p>
 */
@RestController
public class NinepayServiceController {

    private static final Logger log = LoggerFactory.getLogger(NinepayServiceController.class);
    private static final ZoneId NINEPAY_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final long MIN_AMOUNT_VND = 2_000L;
    /** Chars the spec forbids in {@code content} (unaccented alphanumerics expected). */
    private static final String FORBIDDEN_CONTENT_CHARS = "-_|'";

    private final NinepaySimConfig config;
    private final NinepayStore store;
    private final SimSigner signer;
    private final TransferLifecycle lifecycle;
    private final Scenario scenario;

    public NinepayServiceController(NinepaySimConfig config, NinepayStore store, SimSigner signer,
                                    TransferLifecycle lifecycle, Scenario scenario) {
        this.config = config;
        this.store = store;
        this.signer = signer;
        this.lifecycle = lifecycle;
        this.scenario = scenario;
    }

    // -------------------------------------------------------------------------
    // 3.1 POST /service/account/verify — beneficiary name resolve
    // -------------------------------------------------------------------------

    @PostMapping("/service/account/verify")
    public ResponseEntity<Map<String, Object>> verifyAccount(@RequestBody JsonNode body) {
        String requestId = text(body, "request_id");
        String partnerId = text(body, "partner_id");
        String bankNo = text(body, "bank_no");
        String accountNo = text(body, "account_no");
        String accountType = text(body, "account_type");

        Map<String, Object> gate = gate(body, requestId, partnerId,
                SimSigner.canonical(requestId, partnerId, bankNo, accountNo, accountType));
        if (gate != null) {
            return ResponseEntity.ok(gate);
        }
        if (!store.knownBank(bankNo)) {
            return ResponseEntity.ok(err("1023"));
        }
        NinepayStore.SeededAccount account = store.account(accountNo);
        switch (account.behavior()) {
            case FAIL_NOT_FOUND -> { return ResponseEntity.ok(err("1042")); }
            case FAIL_INVALID -> { return ResponseEntity.ok(err("1041")); }
            case BLOCKED -> { return ResponseEntity.ok(err("1060")); }
            case OK -> { /* resolve below */ }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("request_id", requestId);
        data.put("partner_id", partnerId);
        data.put("bank_no", bankNo);
        data.put("account_no", accountNo);
        data.put("account_type", intOrZero(body, "account_type"));
        data.put("account_name", account.accountName());
        data.put("signature", signer.sign(SimSigner.canonical(
                requestId, partnerId, bankNo, accountNo, accountType, account.accountName())));
        return ResponseEntity.ok(ok(data));
    }

    // -------------------------------------------------------------------------
    // 3.2 POST /service/transfer — create payout
    // -------------------------------------------------------------------------

    @PostMapping("/service/transfer")
    public ResponseEntity<Map<String, Object>> transfer(@RequestBody JsonNode body) throws InterruptedException {
        String requestId = text(body, "request_id");
        String partnerId = text(body, "partner_id");
        String bankNo = text(body, "bank_no");
        String accountNo = text(body, "account_no");
        String accountTypeText = text(body, "account_type");
        String accountName = text(body, "account_name");
        String amountText = text(body, "amount");
        String content = text(body, "content");

        Map<String, Object> gate = gate(body, requestId, partnerId, SimSigner.canonical(
                requestId, partnerId, bankNo, accountNo, accountTypeText, accountName, amountText, content));
        if (gate != null) {
            return ResponseEntity.ok(gate);
        }

        // Scenario: no-response drill — stall, then 1063 (partner must poll, never resubmit).
        if (scenario.getTransferMode() == Scenario.TransferMode.TIMEOUT) {
            Thread.sleep(config.getTimeoutHoldMs());
            return ResponseEntity.ok(err("1063"));
        }
        // Scenario: synchronous decline (1024 insufficient / 1065 issuing / 1066 settlement...).
        if (scenario.getTransferMode() == Scenario.TransferMode.FAIL_SYNC) {
            return ResponseEntity.ok(err(scenario.getSyncErrorCode()));
        }

        // Field validation (1008) — amounts are INTEGER VND >= 2000.
        JsonNode amountNode = body.path("amount");
        if (requestId == null || requestId.isBlank() || requestId.length() > 50
                || accountNo == null || accountNo.isBlank()
                || accountName == null || accountName.isBlank()
                || content == null || content.isBlank()) {
            return ResponseEntity.ok(err("1008"));
        }
        int accountType = intOrZero(body, "account_type");
        if (accountType != 0 && accountType != 1) {
            return ResponseEntity.ok(err("1008"));
        }
        if (!amountNode.isIntegralNumber() || amountNode.asLong() < MIN_AMOUNT_VND) {
            return ResponseEntity.ok(err("1008"));
        }
        if (content.chars().anyMatch(c -> FORBIDDEN_CONTENT_CHARS.indexOf(c) >= 0)) {
            return ResponseEntity.ok(err("1008"));
        }
        if (!store.knownBank(bankNo)) {
            return ResponseEntity.ok(err("1023"));
        }
        long amount = amountNode.asLong();

        NinepayStore.SeededAccount account = store.account(accountNo);
        if (account.behavior() == NinepayStore.AccountBehavior.BLOCKED) {
            return ResponseEntity.ok(err("1060"));
        }

        // Terminal plan captured at submission: seeded fail-accounts beat the scenario.
        TransferRecord.Outcome outcome;
        String failCode;
        switch (account.behavior()) {
            case FAIL_NOT_FOUND -> { outcome = TransferRecord.Outcome.FAIL; failCode = "002"; }
            case FAIL_INVALID -> { outcome = TransferRecord.Outcome.FAIL; failCode = "006"; }
            default -> {
                outcome = switch (scenario.getIpnOutcome()) {
                    case SUCCESS -> TransferRecord.Outcome.SUCCESS;
                    case FAIL -> TransferRecord.Outcome.FAIL;
                    case HELD -> TransferRecord.Outcome.HELD;
                    case REVERSAL -> TransferRecord.Outcome.REVERSAL;
                };
                failCode = scenario.getIpnFailCode();
            }
        }

        ZonedDateTime now = ZonedDateTime.now(NINEPAY_ZONE);
        String createdAt = TIME_FORMAT.format(now);
        long fee = config.getFeeVnd();
        TransferRecord record = new TransferRecord(
                requestId, store.nextTransactionId(DAY_FORMAT.format(now)), bankNo, accountNo,
                accountType, accountName, amount, fee, content, createdAt, outcome, failCode);

        // Idempotency: request_id is the key — duplicate → 1062 (spec section 8).
        if (store.byRequestId(requestId) != null) {
            return ResponseEntity.ok(err("1062"));
        }
        // Prefunded balance: debit amount+fee up front; async FAIL / 009 reversal restore it.
        // Debit BEFORE registering so a 1024 rejection does not poison the request_id.
        if (!store.debit(record.getTransferAmountVnd())) {
            return ResponseEntity.ok(err("1024"));
        }
        if (!store.register(record)) { // lost a duplicate race — undo the debit
            store.credit(record.getTransferAmountVnd());
            return ResponseEntity.ok(err("1062"));
        }
        lifecycle.schedule(record);
        log.debug("transfer accepted: request_id={} txn={} amount={} plan={}",
                requestId, record.getTransactionId(), amount, outcome);

        Map<String, Object> data = transferData(record, partnerId, true);
        return ResponseEntity.ok(ok(data));
    }

    // -------------------------------------------------------------------------
    // 3.3 POST /service/transfer/info — lookup (advances the lifecycle when enabled)
    // -------------------------------------------------------------------------

    @PostMapping("/service/transfer/info")
    public ResponseEntity<Map<String, Object>> transferInfo(@RequestBody JsonNode body) {
        String requestId = text(body, "request_id");
        String partnerId = text(body, "partner_id");
        String contentType = text(body, "content_type");
        String key = text(body, "transaction_id"); // overloaded: 9Pay txn id OR original request_id

        Map<String, Object> gate = gate(body, requestId, partnerId,
                SimSigner.canonical(requestId, partnerId, key));
        if (gate != null) {
            return ResponseEntity.ok(gate);
        }

        TransferRecord record = "TRANSACTION_ID".equalsIgnoreCase(contentType)
                ? store.byTransactionId(key)
                : store.byRequestId(key); // TRANSACTION_REQUEST_ID (and the sensible default)
        if (record == null) {
            return ResponseEntity.ok(err("1021"));
        }
        lifecycle.advanceOnPoll(record);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("request_id", requestId);
        data.put("partner_id", partnerId);
        data.put("transaction_id", record.getTransactionId());
        data.put("bank_no", record.getBankNo());
        data.put("account_no", record.getAccountNo());
        data.put("account_type", record.getAccountType());
        data.put("account_name", record.getAccountName());
        data.put("request_amount", record.getAmountVnd());
        data.put("transfer_amount", record.getTransferAmountVnd());
        data.put("content", record.getContent());
        data.put("status", record.getStatus());
        data.put("created_at", record.getCreatedAt());
        data.put("signature", signer.sign(SimSigner.canonical(
                requestId, partnerId, record.getTransactionId(), record.getStatus(), record.getCreatedAt())));
        return ResponseEntity.ok(ok(data));
    }

    // -------------------------------------------------------------------------
    // 3.4 POST /service/account/balance — prefunded balance
    // -------------------------------------------------------------------------

    @PostMapping("/service/account/balance")
    public ResponseEntity<Map<String, Object>> balance(@RequestBody JsonNode body) {
        String requestId = text(body, "request_id");
        String partnerId = text(body, "partner_id");
        String requestTime = text(body, "request_time");

        Map<String, Object> gate = gate(body, requestId, partnerId,
                SimSigner.canonical(requestId, partnerId, requestTime));
        if (gate != null) {
            return ResponseEntity.ok(gate);
        }

        long balance = store.balance();
        String responseTime = TIME_FORMAT.format(ZonedDateTime.now(NINEPAY_ZONE));

        Map<String, Object> service = new LinkedHashMap<>();
        service.put("id", 1);
        service.put("name", "Disbursement");
        service.put("code", "TRANSFER_BANK");
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("Title", "Available balance");
        info.put("Balance", balance);
        info.put("Unit", "VND");
        info.put("Type", "available");
        info.put("Service", service);
        Map<String, Object> available = new LinkedHashMap<>();
        available.put("unit", "VND");
        available.put("value", balance);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("request_id", requestId);
        data.put("partner_id", partnerId);
        data.put("response_time", responseTime);
        data.put("balance_info", List.of(info));
        data.put("balance_available", List.of(available));
        data.put("signature", signer.sign(SimSigner.canonical(requestId, partnerId, responseTime)));
        return ResponseEntity.ok(ok(data));
    }

    // -------------------------------------------------------------------------
    // 3.6 GET /transfer-bank/bank-list — seeded VN bank/wallet roster (no signature per spec)
    // -------------------------------------------------------------------------

    @GetMapping("/transfer-bank/bank-list")
    public ResponseEntity<Map<String, Object>> bankList() {
        return ResponseEntity.ok(ok(Map.of("banks", store.banks())));
    }

    // -------------------------------------------------------------------------
    // 3.7 POST /service/exchange-rate-v2 — seeded reference rates
    // -------------------------------------------------------------------------

    @PostMapping("/service/exchange-rate-v2")
    public ResponseEntity<Map<String, Object>> exchangeRate(@RequestBody JsonNode body) {
        String requestId = text(body, "request_id");
        String partnerId = text(body, "partner_id");
        String requestTime = text(body, "request_time");
        String amountText = text(body, "amount");
        String currencyFrom = text(body, "currency_from");
        String currencyTo = text(body, "currency_to");

        // Spec gotcha (digest 3.7): amount|currency_from|currency_to ARE in the signed string.
        Map<String, Object> gate = gate(body, requestId, partnerId, SimSigner.canonical(
                requestId, partnerId, requestTime, amountText, currencyFrom, currencyTo));
        if (gate != null) {
            return ResponseEntity.ok(gate);
        }

        double rate = seededRate(currencyFrom, currencyTo);
        long amount = body.path("amount").asLong(0);
        Map<String, Object> rateInfo = new LinkedHashMap<>();
        rateInfo.put("currency_from", currencyFrom);
        rateInfo.put("currency_to", currencyTo);
        rateInfo.put("amount", amount);
        rateInfo.put("rate", rate);
        rateInfo.put("converted_amount", Math.round(amount * rate));

        String responseTime = TIME_FORMAT.format(ZonedDateTime.now(NINEPAY_ZONE));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("request_id", requestId);
        data.put("partner_id", partnerId);
        data.put("response_time", responseTime);
        data.put("rate_info", List.of(rateInfo));
        data.put("signature", signer.sign(SimSigner.canonical(requestId, partnerId, responseTime)));
        return ResponseEntity.ok(ok(data));
    }

    // -------------------------------------------------------------------------
    // 3.8 POST /service/v2/decode-qr — VIETQR / VNPAY decode
    // -------------------------------------------------------------------------

    @PostMapping("/service/v2/decode-qr")
    public ResponseEntity<Map<String, Object>> decodeQr(@RequestBody JsonNode body) {
        String requestId = text(body, "request_id");
        String partnerId = text(body, "partner_id");
        String strQr = text(body, "str_qr");

        // Spec: decode-qr signs ONLY request_id|partner_id (not str_qr).
        Map<String, Object> gate = gate(body, requestId, partnerId,
                SimSigner.canonical(requestId, partnerId));
        if (gate != null) {
            return ResponseEntity.ok(gate);
        }
        if (strQr == null || strQr.isBlank()) {
            return ResponseEntity.ok(err("1106"));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        String[] parts = strQr.split("\\|", -1);
        if (strQr.startsWith("VNPAY|") && parts.length >= 2) {
            // Sim pipe format: VNPAY|account_number[|amount[|account_name[|city[|description]]]]
            data.put("type", "VNPAY");
            data.put("bank_no", "VNPAY");
            data.put("account_number", parts[1]);
            data.put("amount", parts.length > 2 && !parts[2].isBlank() ? Long.parseLong(parts[2]) : null);
            data.put("account_name", parts.length > 3 ? emptyToNull(parts[3]) : null);
            data.put("city", parts.length > 4 ? emptyToNull(parts[4]) : null);
            data.put("description", parts.length > 5 ? emptyToNull(parts[5]) : null);
            data.put("crc", "0000");
            data.put("service", "QRPUSH");
        } else if (strQr.startsWith("VIETQR|") && parts.length >= 3) {
            // Sim pipe format: VIETQR|bank_no|account_number[|amount[|account_name]]
            data.put("type", "VIETQR");
            data.put("bank_no", parts[1]);
            data.put("account_number", parts[2]);
            data.put("amount", parts.length > 3 && !parts[3].isBlank() ? Long.parseLong(parts[3]) : null);
            data.put("account_name", parts.length > 4 ? emptyToNull(parts[4])
                    : store.account(parts[2]).accountName());
            data.put("crc", "0000");
            data.put("service", "QRIBFTTA");
        } else if (strQr.startsWith("000201")) {
            // Real EMVCo payloads: canned VIETQR decode (the sim does not parse EMV TLV).
            data.put("type", "VIETQR");
            data.put("bank_no", "VIETCOMBANK");
            data.put("account_number", "1023020330000");
            data.put("account_name", store.account("1023020330000").accountName());
            data.put("crc", strQr.length() >= 4 ? strQr.substring(strQr.length() - 4) : "0000");
            data.put("service", "QRIBFTTA");
        } else {
            return ResponseEntity.ok(err("1106"));
        }
        return ResponseEntity.ok(ok(data));
    }

    // -------------------------------------------------------------------------
    // shared gates + envelope
    // -------------------------------------------------------------------------

    /** partner_id (1001) + signature (1007) gate; null = pass. */
    private Map<String, Object> gate(JsonNode body, String requestId, String partnerId, String canonical) {
        if (partnerId == null || !partnerId.equals(config.getPartnerId())) {
            return err("1001");
        }
        if (!signer.verifyPartner(canonical, text(body, "signature"))) {
            log.debug("request signature REJECTED for request_id={} canonical={}", requestId, canonical);
            return err("1007");
        }
        return null;
    }

    /** POST /service/transfer response body (spec 4.2 shape, signed over 11 fields). */
    private Map<String, Object> transferData(TransferRecord record, String partnerId, boolean includeFee) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("request_id", record.getRequestId());
        data.put("partner_id", partnerId);
        data.put("transaction_id", record.getTransactionId());
        data.put("bank_no", record.getBankNo());
        data.put("account_no", record.getAccountNo());
        data.put("account_type", record.getAccountType());
        data.put("account_name", record.getAccountName());
        data.put("request_amount", record.getAmountVnd());
        data.put("transfer_amount", record.getTransferAmountVnd());
        data.put("content", record.getContent());
        data.put("status", record.getStatus());
        data.put("created_at", record.getCreatedAt());
        data.put("message", "Transaction received");
        if (includeFee) {
            data.put("fee", record.getFeeVnd());
        }
        data.put("signature", signer.sign(SimSigner.canonical(
                record.getRequestId(), partnerId, record.getTransactionId(), record.getBankNo(),
                record.getAccountNo(), record.getAccountType(), record.getAccountName(),
                record.getAmountVnd(), record.getTransferAmountVnd(), record.getStatus(),
                record.getCreatedAt())));
        return data;
    }

    private static Map<String, Object> ok(Map<String, Object> data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("success", true);
        envelope.put("data", data);
        return envelope;
    }

    static final Map<String, String> ERROR_MESSAGES = Map.ofEntries(
            Map.entry("1000", "App server error"),
            Map.entry("1001", "User not exists"),
            Map.entry("1005", "Request not found"),
            Map.entry("1006", "Bank connection temporarily interrupted"),
            Map.entry("1007", "Signature invalid"),
            Map.entry("1008", "Params invalid"),
            Map.entry("1017", "Invalid credential"),
            Map.entry("1021", "Transaction not exists"),
            Map.entry("1023", "No bank information found"),
            Map.entry("1024", "Insufficient balance"),
            Map.entry("1040", "Service not active"),
            Map.entry("1041", "Bank account invalid"),
            Map.entry("1042", "Bank account invalid (not found at bank)"),
            Map.entry("1060", "Bank account blocked"),
            Map.entry("1061", "System maintenance"),
            Map.entry("1062", "request_id already taken"),
            Map.entry("1063", "No response from bank"),
            Map.entry("1064", "Beneficiary bank temporarily interrupted"),
            Map.entry("1065", "Issuing bank declined"),
            Map.entry("1066", "Settlement bank declined"),
            Map.entry("1106", "Invalid QRCode"));

    private static Map<String, Object> err(String code) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", ERROR_MESSAGES.getOrDefault(code, "Error " + code));
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("success", false);
        envelope.put("error", error);
        return envelope;
    }

    /** Seeded reference rates (per 1 unit of currency_from, in currency_to). */
    private static double seededRate(String from, String to) {
        String pair = (from == null ? "" : from.toUpperCase(Locale.ROOT)) + ">"
                + (to == null ? "" : to.toUpperCase(Locale.ROOT));
        return switch (pair) {
            case "USD>VND" -> 25_400.0;
            case "VND>USD" -> 1.0 / 25_400.0;
            case "KRW>VND" -> 18.5;
            case "VND>KRW" -> 1.0 / 18.5;
            default -> 1.0;
        };
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static int intOrZero(JsonNode node, String field) {
        return node.path(field).asInt(0);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
