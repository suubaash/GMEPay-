package com.gme.sim.sendmn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for the SendMN simulator (wire contract of SendmnAuthClient /
 * SendmnSchemeApiClient — raw-token Authorization + Username/AgentCode headers,
 * plain-JSON {"encryptedData": base64(json)} envelope).
 *  T01 authentication happy path returns token + 90-min note
 *  T02 authentication credential errors (S201 / S103 / S101)
 *  T03 verify -> confirm -> status happy path (Approved on 1st poll, default)
 *  T04 approve-after-2-polls: Processing then Approved
 *  T05 duplicate TX_TOKEN_NO on Confirm -> 304
 *  T06 SETTLEMENT_AMOUNT mismatch -> 307
 *  T07 missing token -> 101; invalid token -> S102
 *  T08 token expiry -> S104, re-auth recovers
 *  T09 unknown QR -> 999; unknown TX_TOKEN_NO status -> 303
 *  T10 scenario force-307 + reset
 *  T11 never-approve keeps Processing
 *  T12 fixed-amount QR carries LOCAL_PAYMENT_AMOUNT; /sim/qr/{id} serves the payload
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class SendmnSimControllerTest {

    private static final String USERNAME = "sendmn-username-placeholder";
    private static final String AGENT_CODE = "sendmn-agentcode-placeholder";
    private static final String AUTH_KEY = "sendmn-authkey-placeholder";
    private static final BigDecimal RATE = new BigDecimal("3373.00");
    private static final DateTimeFormatter PAY_DT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    // ------------------------------------------------------------------ helpers

    private String authToken() throws Exception {
        MvcResult res = mvc.perform(post("/api/Authentication")
                        .header("Username", USERNAME)
                        .header("AgentCode", AGENT_CODE)
                        .header("AuthKey", AUTH_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andReturn();
        return mapper.readTree(res.getResponse().getContentAsString()).at("/detail/token").asText();
    }

    private String envelope(Map<String, Object> payload) throws Exception {
        String json = mapper.writeValueAsString(payload);
        String data = Base64.getEncoder().encodeToString(json.getBytes());
        return mapper.writeValueAsString(Map.of("encryptedData", data));
    }

    /** POSTs a business endpoint with full auth headers and returns the DECRYPTED body. */
    private JsonNode call(String uri, String token, Map<String, Object> payload) throws Exception {
        MvcResult res = mvc.perform(post(uri)
                        .header("Authorization", token)
                        .header("Username", USERNAME)
                        .header("AgentCode", AGENT_CODE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(envelope(payload)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode outer = mapper.readTree(res.getResponse().getContentAsString());
        assertThat(outer.hasNonNull("encryptedData")).as("response must be enveloped").isTrue();
        return mapper.readTree(new String(Base64.getDecoder().decode(outer.get("encryptedData").asText())));
    }

    private JsonNode merchants() throws Exception {
        MvcResult res = mvc.perform(get("/sim/merchants")).andExpect(status().isOk()).andReturn();
        return mapper.readTree(res.getResponse().getContentAsString());
    }

    /** First seeded merchant WITHOUT a fixed amount (static QR, user keys the amount in). */
    private JsonNode staticMerchant() throws Exception {
        for (JsonNode m : merchants()) {
            if (!m.hasNonNull("fixedAmount")) {
                return m;
            }
        }
        throw new AssertionError("no amount-less merchant seeded");
    }

    private Map<String, Object> confirmPayload(String tx, String merchantId, String localAmount) {
        BigDecimal local = new BigDecimal(localAmount);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("TX_TOKEN_NO", tx);
        p.put("MERCHANT_ID", merchantId);
        p.put("LOCAL_CUR_CODE", "MNT");
        p.put("LOCAL_PAYMENT_AMOUNT", local.toPlainString());
        p.put("FX_CUR_CODE", "USD");
        p.put("FX_CUR_CD", "USD");
        p.put("FX_USD_BUY_RATE", RATE.toPlainString());
        p.put("FX_USD_SELL_RATE", "");
        p.put("FX_BASIC_RATE", "");
        p.put("FX_TICKER_NO", "");
        p.put("SETTLEMENT_CUR_CODE", "USD");
        p.put("SETTLEMENT_AMOUNT", local.divide(RATE, 4, RoundingMode.HALF_UP).toPlainString());
        p.put("SETTLEMENT_DATE", "");
        p.put("RECONCILE_DATE", "");
        p.put("PAYMENT_DATETIME", PAY_DT.format(Instant.now()));
        return p;
    }

    private void setScenario(Map<String, Object> body) throws Exception {
        mvc.perform(post("/sim/scenario").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andExpect(status().isOk());
    }

    private void resetScenario() throws Exception {
        mvc.perform(post("/sim/scenario/reset")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ tests

    // T01
    @Test
    void t01_authenticationReturnsToken() throws Exception {
        mvc.perform(post("/api/Authentication")
                        .header("Username", USERNAME)
                        .header("AgentCode", AGENT_CODE)
                        .header("AuthKey", AUTH_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.detail.token").isNotEmpty())
                .andExpect(jsonPath("$.detail.note").value("Token will only be Valid for 90 minutes."))
                .andExpect(jsonPath("$.detail.processId").isNotEmpty());
    }

    // T02
    @Test
    void t02_authenticationCredentialErrors() throws Exception {
        mvc.perform(post("/api/Authentication")
                        .header("AgentCode", AGENT_CODE).header("AuthKey", AUTH_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("S201"));
        mvc.perform(post("/api/Authentication")
                        .header("Username", "wrong-user")
                        .header("AgentCode", AGENT_CODE).header("AuthKey", AUTH_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("S103"));
        mvc.perform(post("/api/Authentication")
                        .header("Username", USERNAME)
                        .header("AgentCode", AGENT_CODE).header("AuthKey", "wrong-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("S101"));
    }

    // T03 — full happy path: VerifyQr -> Confirm -> PaymentStatus (Approved on 1st poll)
    @Test
    void t03_verifyConfirmStatusHappyPath() throws Exception {
        String token = authToken();
        JsonNode merchant = staticMerchant();
        String tx = "SMN-T03-" + System.nanoTime();

        JsonNode verify = call("/api/Partner/VerifyQr", token, Map.of(
                "QR_CODE", merchant.get("qrCode").asText(), "TX_TOKEN_NO", tx));
        assertThat(verify.get("RES_CODE").asText()).isEqualTo("0");
        assertThat(verify.get("QR_TYPE").asText()).isEqualTo("11");
        assertThat(verify.get("MERCHANT_ID").asText()).isEqualTo(merchant.get("merchantId").asText());
        assertThat(verify.get("MERCHANT_NAME").asText()).isEqualTo(merchant.get("name").asText());
        assertThat(verify.get("TX_TOKEN_NO").asText()).isEqualTo(tx);
        assertThat(verify.hasNonNull("LOCAL_PAYMENT_AMOUNT")).as("static QR carries no amount").isFalse();

        JsonNode confirm = call("/api/Partner/Confirm", token,
                confirmPayload(tx, merchant.get("merchantId").asText(), "100000.00"));
        assertThat(confirm.get("RES_CODE").asText()).isEqualTo("0");
        assertThat(confirm.get("PAYMENT_NO").asText()).isNotBlank();
        assertThat(confirm.get("PAYMENT_RECIPT_NO").asText()).startsWith("GME"); // doc's misspelling
        assertThat(confirm.get("MERCHANT_NAME").asText()).isEqualTo(merchant.get("name").asText());

        // Before any poll the record is Processing (visible on the sim console)
        mvc.perform(get("/sim/payments/{tx}", tx))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Processing"));

        // approve-after-polls default 1 -> first poll returns Approved
        JsonNode statusResp = call("/api/Partner/PaymentStatus", token, Map.of("TX_TOKEN_NO", tx));
        assertThat(statusResp.get("RES_CODE").asText()).isEqualTo("0");
        assertThat(statusResp.get("PAYMENT_STATUS").asText()).isEqualTo("Approved");
        assertThat(statusResp.get("PAYMENT_NO").asText()).isEqualTo(confirm.get("PAYMENT_NO").asText());
        assertThat(statusResp.get("PAYMENT_RECIPT_NO").asText())
                .isEqualTo(confirm.get("PAYMENT_RECIPT_NO").asText());
        assertThat(statusResp.get("SETTLEMENT_DATE").asText()).isNotBlank();
    }

    // T04 — approve-after-2-polls: Processing on 1st poll, Approved on 2nd
    @Test
    void t04_processingThenApproved() throws Exception {
        String token = authToken();
        JsonNode merchant = staticMerchant();
        String tx = "SMN-T04-" + System.nanoTime();
        setScenario(Map.of("approveAfterPolls", 2));
        try {
            call("/api/Partner/Confirm", token,
                    confirmPayload(tx, merchant.get("merchantId").asText(), "50000.00"));
            JsonNode first = call("/api/Partner/PaymentStatus", token, Map.of("TX_TOKEN_NO", tx));
            assertThat(first.get("PAYMENT_STATUS").asText()).isEqualTo("Processing");
            JsonNode second = call("/api/Partner/PaymentStatus", token, Map.of("TX_TOKEN_NO", tx));
            assertThat(second.get("PAYMENT_STATUS").asText()).isEqualTo("Approved");
        } finally {
            resetScenario();
        }
    }

    // T05 — duplicate TX_TOKEN_NO on Confirm -> 304
    @Test
    void t05_duplicateTxTokenNo304() throws Exception {
        String token = authToken();
        JsonNode merchant = staticMerchant();
        String tx = "SMN-T05-" + System.nanoTime();
        Map<String, Object> payload = confirmPayload(tx, merchant.get("merchantId").asText(), "75000.00");
        assertThat(call("/api/Partner/Confirm", token, payload).get("RES_CODE").asText()).isEqualTo("0");
        JsonNode dup = call("/api/Partner/Confirm", token, payload);
        assertThat(dup.get("RES_CODE").asText()).isEqualTo("304");
        assertThat(dup.get("RES_MSG").asText()).contains("duplicated");
    }

    // T06 — SETTLEMENT_AMOUNT that doesn't match the registered rate -> 307
    @Test
    void t06_settlementMismatch307() throws Exception {
        String token = authToken();
        JsonNode merchant = staticMerchant();
        Map<String, Object> payload = confirmPayload(
                "SMN-T06-" + System.nanoTime(), merchant.get("merchantId").asText(), "100000.00");
        payload.put("SETTLEMENT_AMOUNT", "999.9999"); // wildly off LOCAL/RATE
        JsonNode resp = call("/api/Partner/Confirm", token, payload);
        assertThat(resp.get("RES_CODE").asText()).isEqualTo("307");
    }

    // T07 — missing token -> 101; garbage token -> S102 (both ride inside the envelope)
    @Test
    void t07_tokenMissingAndInvalid() throws Exception {
        JsonNode merchant = staticMerchant();
        Map<String, Object> payload = Map.of(
                "QR_CODE", merchant.get("qrCode").asText(),
                "TX_TOKEN_NO", "SMN-T07-" + System.nanoTime());

        MvcResult res = mvc.perform(post("/api/Partner/VerifyQr")
                        .header("Username", USERNAME).header("AgentCode", AGENT_CODE)
                        .contentType(MediaType.APPLICATION_JSON).content(envelope(payload)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode outer = mapper.readTree(res.getResponse().getContentAsString());
        JsonNode noToken = mapper.readTree(new String(
                Base64.getDecoder().decode(outer.get("encryptedData").asText())));
        assertThat(noToken.get("RES_CODE").asText()).isEqualTo("101");

        JsonNode badToken = call("/api/Partner/VerifyQr", "not-a-real-token", payload);
        assertThat(badToken.get("RES_CODE").asText()).isEqualTo("S102");
    }

    // T08 — expired token -> S104; fresh authentication recovers
    @Test
    void t08_tokenExpiryS104AndReauth() throws Exception {
        String token = authToken();
        JsonNode merchant = staticMerchant();
        Map<String, Object> payload = Map.of(
                "QR_CODE", merchant.get("qrCode").asText(),
                "TX_TOKEN_NO", "SMN-T08-" + System.nanoTime());
        assertThat(call("/api/Partner/VerifyQr", token, payload).get("RES_CODE").asText()).isEqualTo("0");

        mvc.perform(post("/sim/expire-tokens")).andExpect(status().isOk());
        assertThat(call("/api/Partner/VerifyQr", token, payload).get("RES_CODE").asText()).isEqualTo("S104");

        String fresh = authToken();
        assertThat(call("/api/Partner/VerifyQr", fresh, payload).get("RES_CODE").asText()).isEqualTo("0");
    }

    // T09 — unknown QR -> 999; unknown TX_TOKEN_NO on status -> 303
    @Test
    void t09_unknownQrAndUnknownToken() throws Exception {
        String token = authToken();
        JsonNode badQr = call("/api/Partner/VerifyQr", token, Map.of(
                "QR_CODE", "00020101021199990000notregistered63040000",
                "TX_TOKEN_NO", "SMN-T09-" + System.nanoTime()));
        assertThat(badQr.get("RES_CODE").asText()).isEqualTo("999");

        JsonNode unknown = call("/api/Partner/PaymentStatus", token,
                Map.of("TX_TOKEN_NO", "SMN-NEVER-SEEN-" + System.nanoTime()));
        assertThat(unknown.get("RES_CODE").asText()).isEqualTo("303");
    }

    // T10 — scenario force-307 rejects even a correct Confirm; reset restores success
    @Test
    void t10_scenarioForce307AndReset() throws Exception {
        String token = authToken();
        JsonNode merchant = staticMerchant();
        setScenario(Map.of("forceError307", true));
        try {
            JsonNode forced = call("/api/Partner/Confirm", token, confirmPayload(
                    "SMN-T10a-" + System.nanoTime(), merchant.get("merchantId").asText(), "60000.00"));
            assertThat(forced.get("RES_CODE").asText()).isEqualTo("307");
        } finally {
            resetScenario();
        }
        JsonNode after = call("/api/Partner/Confirm", token, confirmPayload(
                "SMN-T10b-" + System.nanoTime(), merchant.get("merchantId").asText(), "60000.00"));
        assertThat(after.get("RES_CODE").asText()).isEqualTo("0");
    }

    // T11 — never-approve: stays Processing over repeated polls
    @Test
    void t11_neverApproveStaysProcessing() throws Exception {
        String token = authToken();
        JsonNode merchant = staticMerchant();
        String tx = "SMN-T11-" + System.nanoTime();
        setScenario(Map.of("neverApprove", true));
        try {
            call("/api/Partner/Confirm", token,
                    confirmPayload(tx, merchant.get("merchantId").asText(), "45000.00"));
            for (int i = 0; i < 3; i++) {
                JsonNode poll = call("/api/Partner/PaymentStatus", token, Map.of("TX_TOKEN_NO", tx));
                assertThat(poll.get("PAYMENT_STATUS").asText()).isEqualTo("Processing");
            }
        } finally {
            resetScenario();
        }
    }

    // T12 — amount-carrying QR returns LOCAL_PAYMENT_AMOUNT; /sim/qr serves the payload
    @Test
    void t12_fixedAmountQrAndQrEndpoint() throws Exception {
        String token = authToken();
        JsonNode withAmount = null;
        for (JsonNode m : merchants()) {
            if (m.hasNonNull("fixedAmount")) {
                withAmount = m;
            }
        }
        assertThat(withAmount).as("a fixed-amount merchant is seeded").isNotNull();

        MvcResult qrRes = mvc.perform(get("/sim/qr/{id}", withAmount.get("merchantId").asText()))
                .andExpect(status().isOk())
                .andReturn();
        String qr = qrRes.getResponse().getContentAsString();
        assertThat(qr).isEqualTo(withAmount.get("qrCode").asText());

        JsonNode verify = call("/api/Partner/VerifyQr", token, Map.of(
                "QR_CODE", qr, "TX_TOKEN_NO", "SMN-T12-" + System.nanoTime()));
        assertThat(verify.get("RES_CODE").asText()).isEqualTo("0");
        assertThat(verify.get("LOCAL_PAYMENT_AMOUNT").asText())
                .isEqualTo(withAmount.get("fixedAmount").asText());
    }
}
