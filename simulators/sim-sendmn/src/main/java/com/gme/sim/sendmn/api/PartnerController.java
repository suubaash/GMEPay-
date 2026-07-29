package com.gme.sim.sendmn.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.sim.sendmn.config.SimSendmnProperties;
import com.gme.sim.sendmn.envelope.PlainEnvelope;
import com.gme.sim.sendmn.fx.FxState;
import com.gme.sim.sendmn.model.Merchant;
import com.gme.sim.sendmn.model.MerchantRegistry;
import com.gme.sim.sendmn.model.PaymentRecord;
import com.gme.sim.sendmn.model.PaymentStore;
import com.gme.sim.sendmn.scenario.ScenarioState;
import com.gme.sim.sendmn.token.TokenStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The three SendMN business endpoints (all POST, token-guarded, enveloped):
 * /api/Partner/VerifyQr, /api/Partner/Confirm, /api/Partner/PaymentStatus.
 *
 * <p>Wire contract (matches SendmnSchemeApiClient exactly): headers
 * {@code Authorization: <raw token>} (no Bearer) + {@code Username}/{@code AgentCode};
 * request and response bodies are {@code {"encryptedData": base64(json)}} (plain-JSON
 * envelope mode — adapter's PlainJsonEnvelopeCodec default). Business errors ride
 * <b>inside</b> the decrypted body as RES_CODE with HTTP 200 — including the
 * 401-equivalents (101 token missing, S102 invalid, S104 expired) — because the
 * adapter's re-auth logic reads RES_CODE from the parsed body.</p>
 */
@RestController
public class PartnerController {

    private static final Logger log = LoggerFactory.getLogger(PartnerController.class);

    private static final Pattern PAYMENT_DATETIME = Pattern.compile("\\d{14}"); // UTC yyyyMMddHHmmss
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private final SimSendmnProperties props;
    private final TokenStore tokenStore;
    private final MerchantRegistry merchants;
    private final PaymentStore payments;
    private final FxState fxState;
    private final ScenarioState scenario;
    private final ObjectMapper mapper;

    public PartnerController(SimSendmnProperties props, TokenStore tokenStore,
                             MerchantRegistry merchants, PaymentStore payments,
                             FxState fxState, ScenarioState scenario, ObjectMapper mapper) {
        this.props = props;
        this.tokenStore = tokenStore;
        this.merchants = merchants;
        this.payments = payments;
        this.fxState = fxState;
        this.scenario = scenario;
        this.mapper = mapper;
    }

    // -------------------------------------------------------------------------
    // VerifyQr — decode a scanned merchant QR into merchant info
    // -------------------------------------------------------------------------

    @PostMapping("/api/Partner/VerifyQr")
    public ResponseEntity<Map<String, String>> verifyQr(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String token,
            @RequestHeader(name = "Username", required = false) String username,
            @RequestHeader(name = "AgentCode", required = false) String agentCode,
            @RequestBody(required = false) Map<String, String> envelope) {
        scenario.applyDelay();
        Map<String, Object> guard = guard(token, username, agentCode);
        if (guard != null) {
            return enveloped(guard);
        }
        JsonNode body = decode(envelope);
        if (body == null) {
            return enveloped(err("997", "encryptedData is missing!"));
        }
        String qrCode = text(body, "QR_CODE");
        String txTokenNo = text(body, "TX_TOKEN_NO");
        if (isBlank(qrCode)) {
            return enveloped(err("203", "QR_CODE is missing!"));
        }
        if (isBlank(txTokenNo)) {
            return enveloped(err("204", "TX_TOKEN_NO is missing!"));
        }
        Optional<Merchant> found = merchants.byQr(qrCode);
        if (found.isEmpty()) {
            // Unknown QR: the doc has no dedicated code; the decode hop is QPay's (999).
            return enveloped(err("999", "QPay server error!", "QR could not be decoded"));
        }
        Merchant m = found.get();
        payments.recordDecrypted(txTokenNo, m);
        log.debug("VerifyQr tx={} merchant={}", txTokenNo, m.name());

        Map<String, Object> resp = ok();
        resp.put("QR_TYPE", "11");
        resp.put("MERCHANT_ID", m.merchantId());
        resp.put("MERCHANT_NAME", m.name());
        resp.put("MERCHANT_ADDRESS", m.address());
        resp.put("TERMINAL_ID", m.terminalId());
        resp.put("TX_TOKEN_NO", txTokenNo);
        // static QR may omit the amount -> null (user keys it in)
        resp.put("LOCAL_PAYMENT_AMOUNT", m.fixedAmount() == null ? null : m.fixedAmount().toPlainString());
        return enveloped(resp);
    }

    // -------------------------------------------------------------------------
    // Confirm — the money-moving call
    // -------------------------------------------------------------------------

    @PostMapping("/api/Partner/Confirm")
    public ResponseEntity<Map<String, String>> confirm(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String token,
            @RequestHeader(name = "Username", required = false) String username,
            @RequestHeader(name = "AgentCode", required = false) String agentCode,
            @RequestBody(required = false) Map<String, String> envelope) {
        scenario.applyDelay();
        Map<String, Object> guard = guard(token, username, agentCode);
        if (guard != null) {
            return enveloped(guard);
        }
        JsonNode body = decode(envelope);
        if (body == null) {
            return enveloped(err("997", "encryptedData is missing!"));
        }

        String txTokenNo = text(body, "TX_TOKEN_NO");
        String merchantId = text(body, "MERCHANT_ID");
        String localCurCode = text(body, "LOCAL_CUR_CODE");
        String localAmountStr = text(body, "LOCAL_PAYMENT_AMOUNT");
        String fxUsdBuyRateStr = text(body, "FX_USD_BUY_RATE");
        String settlementAmountStr = text(body, "SETTLEMENT_AMOUNT");
        String paymentDatetime = text(body, "PAYMENT_DATETIME");

        if (isBlank(txTokenNo)) return enveloped(err("204", "TX_TOKEN_NO is missing!"));
        if (isBlank(merchantId)) return enveloped(err("205", "MERCHANT_ID is missing!"));
        if (isBlank(localCurCode)) return enveloped(err("206", "LOCAL_CUR_CODE is missing!"));
        if (isBlank(localAmountStr)) return enveloped(err("207", "LOCAL_PAYMENT_AMOUNT is missing!"));
        if (isBlank(fxUsdBuyRateStr)) return enveloped(err("208", "FX_USD_BUY_RATE is missing!"));
        if (isBlank(settlementAmountStr)) return enveloped(err("210", "SETTLEMENT_AMOUNT is missing!"));
        if (!"MNT".equals(localCurCode)) {
            return enveloped(err("311", "LOCAL_CUR_CODE is incorrect format!"));
        }
        if (isBlank(paymentDatetime) || !PAYMENT_DATETIME.matcher(paymentDatetime).matches()) {
            return enveloped(err("316", "PAYMENT_DATETIME is incorrect format! (UTC yyyyMMddHHmmss)"));
        }

        if (scenario.isForceError304()) {
            return enveloped(err("304", "TX_TOKEN_NO is duplicated!", "forced by /sim/scenario"));
        }

        Optional<Merchant> found = merchants.byMerchantId(merchantId);
        if (found.isEmpty()) {
            return enveloped(err("305", "MERCHANT_ID is invalid!"));
        }
        Merchant m = found.get();

        BigDecimal localAmount;
        BigDecimal fxUsdBuyRate;
        BigDecimal settlementAmount;
        try {
            localAmount = new BigDecimal(localAmountStr);
            fxUsdBuyRate = new BigDecimal(fxUsdBuyRateStr);
            settlementAmount = new BigDecimal(settlementAmountStr);
        } catch (NumberFormatException e) {
            return enveloped(err("991", "Exception error!", "unparseable amount/rate: " + e.getMessage()));
        }

        // 307: SETTLEMENT_AMOUNT re-check against the REGISTERED rate (doc section 9) —
        // both the rate itself and LOCAL/RATE at scale 4 HALF_UP must match.
        boolean rateMismatch = fxUsdBuyRate.compareTo(fxState.rate()) != 0;
        boolean amountMismatch = fxState.settlementAmount(localAmount).compareTo(settlementAmount) != 0;
        if (scenario.isForceError307() || rateMismatch || amountMismatch) {
            return enveloped(err("307", "SETTLEMENT_AMOUNT is not matched!",
                    "registered rate=" + fxState.rate().toPlainString()
                            + " expected=" + fxState.settlementAmount(localAmount).toPlainString()));
        }

        Optional<PaymentRecord> confirmed = payments.confirm(txTokenNo, m);
        if (confirmed.isEmpty()) {
            return enveloped(err("304", "TX_TOKEN_NO is duplicated!"));
        }
        PaymentRecord record = confirmed.get();
        record.setLocalAmount(localAmount);
        record.setSettlementAmount(settlementAmount);
        log.debug("Confirm tx={} merchant={} paymentNo={}", txTokenNo, m.name(), record.getPaymentNo());

        Map<String, Object> resp = ok();
        resp.put("PAYMENT_NO", record.getPaymentNo());
        resp.put("PAYMENT_RECIPT_NO", record.getPaymentReceiptNo()); // doc's misspelling, kept
        resp.put("MERCHANT_ID", m.numericId()); // Confirm echoes the numeric id (digest gotcha)
        resp.put("MERCHANT_NAME", m.name());
        return enveloped(resp);
    }

    // -------------------------------------------------------------------------
    // PaymentStatus — poll by TX_TOKEN_NO (no callback exists)
    // -------------------------------------------------------------------------

    @PostMapping("/api/Partner/PaymentStatus")
    public ResponseEntity<Map<String, String>> paymentStatus(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String token,
            @RequestHeader(name = "Username", required = false) String username,
            @RequestHeader(name = "AgentCode", required = false) String agentCode,
            @RequestBody(required = false) Map<String, String> envelope) {
        scenario.applyDelay();
        Map<String, Object> guard = guard(token, username, agentCode);
        if (guard != null) {
            return enveloped(guard);
        }
        JsonNode body = decode(envelope);
        if (body == null) {
            return enveloped(err("997", "encryptedData is missing!"));
        }
        String txTokenNo = text(body, "TX_TOKEN_NO");
        if (isBlank(txTokenNo)) {
            return enveloped(err("204", "TX_TOKEN_NO is missing!"));
        }
        Optional<PaymentRecord> found = payments.find(txTokenNo);
        if (found.isEmpty()) {
            return enveloped(err("303", "TX_TOKEN_NO is invalid!"));
        }
        PaymentRecord record = found.get();

        // Processing -> Approved after the configured number of polls (never-approve wins).
        if (record.getState() == PaymentRecord.State.PROCESSING && !scenario.isNeverApprove()
                && record.incrementAndGetPolls() >= scenario.getApproveAfterPolls()) {
            record.setState(PaymentRecord.State.APPROVED);
            String today = LocalDate.now(ZoneOffset.UTC).format(DATE_FMT);
            record.setSettlementDate(today);
            record.setReconcileDate(today);
        }

        Merchant m = record.getMerchant();
        Map<String, Object> resp = ok();
        resp.put("RECONCILE_DATE", record.getReconcileDate());
        resp.put("SETTLEMENT_DATE", record.getSettlementDate());
        resp.put("TX_TOKEN_NO", txTokenNo);
        resp.put("PAYMENT_STATUS", record.statusLabel());
        resp.put("PAYMENT_NO", record.getPaymentNo());
        resp.put("PAYMENT_RECIPT_NO", record.getPaymentReceiptNo());
        resp.put("MERCHANT_ID", m == null ? null : m.numericId());
        resp.put("MERCHANT_NAME", m == null ? null : m.name());
        return enveloped(resp);
    }

    // -------------------------------------------------------------------------
    // Guard + envelope plumbing
    // -------------------------------------------------------------------------

    /** Returns an error body when the token/headers are rejected, null when OK. */
    private Map<String, Object> guard(String token, String username, String agentCode) {
        if (isBlank(token)) {
            return err("101", "Unauthorized! Token is missing in the header.");
        }
        if (isBlank(username)) {
            return err("S201", "Username is missing! Please put Username in the header.");
        }
        if (isBlank(agentCode)) {
            return err("S202", "AgentCode is missing! Please put AgentCode in the header.");
        }
        if (!props.getUsername().equals(username) || !props.getAgentCode().equals(agentCode)) {
            return err("S103", "AgentCode or Username is invalid!");
        }
        return switch (tokenStore.validate(token)) {
            case VALID -> null;
            case EXPIRED -> err("S104", "Token is expired! Please regenerate the token.");
            default -> err("S102", "Token is invalid!");
        };
    }

    /** Unwraps {@code {"encryptedData": base64(json)}}; null when absent/undecodable. */
    private JsonNode decode(Map<String, String> envelope) {
        if (envelope == null) {
            return null;
        }
        String encryptedData = envelope.get("encryptedData");
        if (isBlank(encryptedData)) {
            return null;
        }
        try {
            return mapper.readTree(PlainEnvelope.decode(encryptedData));
        } catch (Exception e) {
            log.debug("envelope decode failed: {}", e.getMessage());
            return null;
        }
    }

    private ResponseEntity<Map<String, String>> enveloped(Map<String, Object> payload) {
        try {
            String json = mapper.writeValueAsString(payload);
            return ResponseEntity.ok(Map.of("encryptedData", PlainEnvelope.encode(json)));
        } catch (Exception e) {
            throw new IllegalStateException("failed to encode envelope", e);
        }
    }

    private static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("RES_CODE", "0");
        m.put("RES_MSG", "Success");
        m.put("ADDITIVE_MSG", null);
        return m;
    }

    private static Map<String, Object> err(String code, String msg) {
        return err(code, msg, null);
    }

    private static Map<String, Object> err(String code, String msg, String additive) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("RES_CODE", code);
        m.put("RES_MSG", msg);
        m.put("ADDITIVE_MSG", additive);
        return m;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
