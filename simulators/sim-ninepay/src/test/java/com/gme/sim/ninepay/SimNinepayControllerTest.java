package com.gme.sim.ninepay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc tests for the 9Pay disbursement simulator.
 *  T01 signature round-trip: partner-signed verify accepted; sim response signature
 *      verifies against GET /sim/public-key (the adapter's canonical pipe format)
 *  T02 tampered request signature -> error 1007
 *  T03 transfer happy path -> PENDING response, then SUCCESS IPN with correct signed
 *      payload; balance debited amount+fee; lookup shows SUCCESS
 *  T04 duplicate request_id -> error 1062
 *  T05 REVERSAL scenario -> SUCCESS IPN (000) then DELAYED second IPN code 009; balance restored
 *  T06 VND validation: amount 1999 -> 1008; non-integer 2000.5 -> 1008
 *  T07 FAIL_SYNC scenario -> error 1024 (insufficient balance drill)
 *  T08 HELD scenario -> IPN code 008, wire status stays PROCESSING
 *  T09 bank-list + decode-qr shapes
 */
@SpringBootTest(properties = {
        "sim.ninepay.lifecycle-step-ms=50",
        "sim.ninepay.reversal-delay-ms=150",
        "sim.ninepay.initial-balance-vnd=50000000",
        "sim.ninepay.fee-vnd=4000",
        // Unroutable-but-fast target: delivery fails, payloads still land in the outbox.
        "sim.ninepay.ipn-url=http://127.0.0.1:59987/scheme/ipn"
})
@AutoConfigureMockMvc
class SimNinepayControllerTest {

    private static final long INITIAL_BALANCE = 50_000_000L;
    private static final long FEE = 4_000L;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private static KeyPair partnerKeys;
    private static final AtomicLong SEQ = new AtomicLong();

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        partnerKeys = generator.generateKeyPair();
    }

    @BeforeEach
    void reset() throws Exception {
        mvc.perform(post("/sim/reset")).andExpect(status().isOk());
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                        .encodeToString(partnerKeys.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        mvc.perform(post("/sim/partner-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("public_key_pem", pem))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verifying_requests").value(true));
    }

    // ------------------------------------------------------------------ helpers

    private static String canonical(Object... fields) {
        StringJoiner joiner = new StringJoiner("|");
        for (Object f : fields) {
            joiner.add(f == null ? "" : String.valueOf(f));
        }
        return joiner.toString();
    }

    private static String sign(String canonical) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(partnerKeys.getPrivate());
        signature.update(canonical.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private PublicKey simPublicKey() throws Exception {
        String body = mvc.perform(get("/sim/public-key")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String pem = mapper.readTree(body).path("public_key_pem").asText();
        byte[] der = Base64.getDecoder().decode(
                pem.replaceAll("-----(BEGIN|END)[^-]*-----", "").replaceAll("\\s", ""));
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private static boolean verifyWith(PublicKey key, String canonical, String signatureB64) throws Exception {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(key);
        signature.update(canonical.getBytes(StandardCharsets.UTF_8));
        return signature.verify(Base64.getDecoder().decode(signatureB64));
    }

    private static String newRequestId() {
        return "GMEPAY9P20260727" + String.format("%06d", SEQ.incrementAndGet());
    }

    private Map<String, Object> transferBody(String requestId, long amount) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("partner_id", "GMEPAY");
        body.put("bank_no", "VIETCOMBANK");
        body.put("account_no", "1023020330000");
        body.put("account_type", 0);
        body.put("account_name", "NGUYEN VAN AN");
        body.put("amount", amount);
        body.put("content", "GME remittance payout");
        body.put("signature", sign(canonical(requestId, "GMEPAY", "VIETCOMBANK", "1023020330000",
                0, "NGUYEN VAN AN", amount, "GME remittance payout")));
        return body;
    }

    private JsonNode postJson(String path, Map<String, Object> body) throws Exception {
        String response = mvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response);
    }

    private JsonNode ipns() throws Exception {
        String body = mvc.perform(get("/sim/ipns")).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }

    /** IPN payloads for ONE request_id, in emission order (outbox is shared across tests). */
    private List<JsonNode> ipnsFor(String requestId) throws Exception {
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode entry : ipns()) {
            JsonNode payload = entry.path("payload");
            if (requestId.equals(payload.path("request_id").asText())) {
                matches.add(payload);
            }
        }
        return matches;
    }

    private long simBalance() throws Exception {
        String body = mvc.perform(get("/sim/balance")).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("balance_vnd").asLong();
    }

    private void setScenario(Map<String, Object> patch) throws Exception {
        mvc.perform(post("/sim/scenario")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(patch)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ tests

    // T01 — signer round-trip with the adapter's canonical pipe format.
    @Test
    void t01_signatureRoundTrip_verifyAccount() throws Exception {
        String requestId = newRequestId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("partner_id", "GMEPAY");
        body.put("bank_no", "VIETCOMBANK");
        body.put("account_no", "1023020330000");
        body.put("account_type", 0);
        body.put("signature", sign(canonical(requestId, "GMEPAY", "VIETCOMBANK", "1023020330000", 0)));

        JsonNode envelope = postJson("/service/account/verify", body);
        assertThat(envelope.path("success").asBoolean()).isTrue();
        JsonNode data = envelope.path("data");
        assertThat(data.path("account_name").asText()).isEqualTo("NGUYEN VAN AN");

        // Sim response signature must verify against /sim/public-key over the 6-field canonical.
        String responseCanonical = canonical(
                data.path("request_id").asText(), data.path("partner_id").asText(),
                data.path("bank_no").asText(), data.path("account_no").asText(),
                data.path("account_type").asText(), data.path("account_name").asText());
        assertThat(verifyWith(simPublicKey(), responseCanonical, data.path("signature").asText()))
                .isTrue();
    }

    // T02 — tampered request signature -> 1007.
    @Test
    void t02_tamperedSignature_rejected1007() throws Exception {
        Map<String, Object> body = transferBody(newRequestId(), 10_000L);
        body.put("amount", 999_999L); // amount no longer matches the signed canonical
        JsonNode envelope = postJson("/service/transfer", body);
        assertThat(envelope.path("success").asBoolean()).isFalse();
        assertThat(envelope.path("error").path("code").asText()).isEqualTo("1007");
    }

    // T03 — transfer happy path -> SUCCESS IPN payload correctness + balance debit.
    @Test
    void t03_transferHappyPath_successIpn() throws Exception {
        String requestId = newRequestId();
        JsonNode envelope = postJson("/service/transfer", transferBody(requestId, 10_000L));
        assertThat(envelope.path("success").asBoolean()).isTrue();
        JsonNode data = envelope.path("data");
        assertThat(data.path("status").asText()).isEqualTo("PENDING");
        assertThat(data.path("request_amount").asLong()).isEqualTo(10_000L);
        assertThat(data.path("fee").asLong()).isEqualTo(FEE);
        assertThat(data.path("transfer_amount").asLong()).isEqualTo(10_000L + FEE);
        String transactionId = data.path("transaction_id").asText();
        assertThat(transactionId).startsWith("NP").hasSizeLessThanOrEqualTo(20);

        // Transfer response signature (11-field canonical).
        String responseCanonical = canonical(requestId, "GMEPAY", transactionId,
                "VIETCOMBANK", "1023020330000", "0", "NGUYEN VAN AN",
                "10000", String.valueOf(10_000L + FEE), "PENDING", data.path("created_at").asText());
        assertThat(verifyWith(simPublicKey(), responseCanonical, data.path("signature").asText()))
                .isTrue();

        // Terminal SUCCESS IPN lands in the outbox.
        await().atMost(Duration.ofSeconds(5)).until(() -> !ipnsFor(requestId).isEmpty());
        JsonNode ipn = ipnsFor(requestId).get(0);
        assertThat(ipn.path("request_id").asText()).isEqualTo(requestId);
        assertThat(ipn.path("trans_id").asText()).isEqualTo(transactionId);
        assertThat(ipn.path("type").asText()).isEqualTo("TRANSFER_BANK");
        assertThat(ipn.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(ipn.path("code").asText()).isEqualTo("000");
        assertThat(ipn.path("request_amount").asLong()).isEqualTo(10_000L);
        assertThat(ipn.path("fee").asLong()).isEqualTo(FEE);
        assertThat(ipn.path("transfer_amount").asLong()).isEqualTo(10_000L + FEE);

        // IPN signature: 9 fields, code/message/approved_at NOT signed.
        String ipnCanonical = canonical(requestId, "GMEPAY", transactionId,
                10_000L, FEE, 10_000L + FEE, "TRANSFER_BANK", "SUCCESS",
                ipn.path("created_at").asText());
        assertThat(verifyWith(simPublicKey(), ipnCanonical, ipn.path("signature").asText())).isTrue();

        // Balance debited amount+fee; lookup shows SUCCESS.
        assertThat(simBalance()).isEqualTo(INITIAL_BALANCE - 10_000L - FEE);
        String lookupId = newRequestId();
        Map<String, Object> lookup = new LinkedHashMap<>();
        lookup.put("request_id", lookupId);
        lookup.put("partner_id", "GMEPAY");
        lookup.put("content_type", "TRANSACTION_REQUEST_ID");
        lookup.put("transaction_id", requestId);
        lookup.put("signature", sign(canonical(lookupId, "GMEPAY", requestId)));
        JsonNode info = postJson("/service/transfer/info", lookup);
        assertThat(info.path("data").path("status").asText()).isEqualTo("SUCCESS");
    }

    // T04 — duplicate request_id -> 1062.
    @Test
    void t04_duplicateRequestId_1062() throws Exception {
        String requestId = newRequestId();
        assertThat(postJson("/service/transfer", transferBody(requestId, 10_000L))
                .path("success").asBoolean()).isTrue();
        JsonNode dup = postJson("/service/transfer", transferBody(requestId, 10_000L));
        assertThat(dup.path("success").asBoolean()).isFalse();
        assertThat(dup.path("error").path("code").asText()).isEqualTo("1062");
    }

    // T05 — the critical scenario: delayed post-SUCCESS 009 bank reversal.
    @Test
    void t05_delayedReversal_secondIpn009_balanceRestored() throws Exception {
        setScenario(Map.of("ipnOutcome", "REVERSAL"));
        String requestId = newRequestId();
        postJson("/service/transfer", transferBody(requestId, 25_000L));

        await().atMost(Duration.ofSeconds(5)).until(() -> ipnsFor(requestId).size() >= 2);
        JsonNode first = ipnsFor(requestId).get(0);
        JsonNode second = ipnsFor(requestId).get(1);
        assertThat(first.path("code").asText()).isEqualTo("000");
        assertThat(first.path("status").asText()).isEqualTo("SUCCESS");
        assertThat(second.path("code").asText()).isEqualTo("009");
        assertThat(second.path("request_id").asText()).isEqualTo(requestId);
        assertThat(second.path("trans_id").asText()).isEqualTo(first.path("trans_id").asText());

        // 009 signature verifies too (status field stays SUCCESS; 009 is a message code).
        String canonical009 = canonical(requestId, "GMEPAY", second.path("trans_id").asText(),
                25_000L, FEE, 25_000L + FEE, "TRANSFER_BANK", "SUCCESS",
                second.path("created_at").asText());
        assertThat(verifyWith(simPublicKey(), canonical009, second.path("signature").asText())).isTrue();

        // Reversal restored the debit.
        assertThat(simBalance()).isEqualTo(INITIAL_BALANCE);
    }

    // T06 — VND validation: integer >= 2000.
    @Test
    void t06_vndValidation() throws Exception {
        JsonNode tooSmall = postJson("/service/transfer", transferBody(newRequestId(), 1_999L));
        assertThat(tooSmall.path("error").path("code").asText()).isEqualTo("1008");

        // Non-integer VND: signed over the literal "2000.5" so it passes the signature
        // gate and dies on amount validation.
        String requestId = newRequestId();
        String signature = sign(canonical(requestId, "GMEPAY", "VIETCOMBANK", "1023020330000",
                0, "NGUYEN VAN AN", "2000.5", "GME remittance payout"));
        String raw = "{\"request_id\":\"" + requestId + "\",\"partner_id\":\"GMEPAY\","
                + "\"bank_no\":\"VIETCOMBANK\",\"account_no\":\"1023020330000\",\"account_type\":0,"
                + "\"account_name\":\"NGUYEN VAN AN\",\"amount\":2000.5,"
                + "\"content\":\"GME remittance payout\",\"signature\":\"" + signature + "\"}";
        String response = mvc.perform(post("/service/transfer")
                        .contentType(MediaType.APPLICATION_JSON).content(raw))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mapper.readTree(response).path("error").path("code").asText()).isEqualTo("1008");
    }

    // T07 — FAIL_SYNC scenario: forced 1024 insufficient-balance decline.
    @Test
    void t07_forcedSyncFail_1024() throws Exception {
        setScenario(Map.of("transferMode", "FAIL_SYNC", "syncErrorCode", "1024"));
        JsonNode envelope = postJson("/service/transfer", transferBody(newRequestId(), 10_000L));
        assertThat(envelope.path("success").asBoolean()).isFalse();
        assertThat(envelope.path("error").path("code").asText()).isEqualTo("1024");
        assertThat(simBalance()).isEqualTo(INITIAL_BALANCE); // never debited
    }

    // T08 — HELD scenario: IPN code 008, wire status stays PROCESSING.
    @Test
    void t08_heldScenario_ipn008() throws Exception {
        setScenario(Map.of("ipnOutcome", "HELD"));
        String requestId = newRequestId();
        postJson("/service/transfer", transferBody(requestId, 10_000L));

        await().atMost(Duration.ofSeconds(5)).until(() -> !ipnsFor(requestId).isEmpty());
        JsonNode ipn = ipnsFor(requestId).get(0);
        assertThat(ipn.path("code").asText()).isEqualTo("008");
        assertThat(ipn.path("status").asText()).isEqualTo("PROCESSING");

        String lookupId = newRequestId();
        Map<String, Object> lookup = new LinkedHashMap<>();
        lookup.put("request_id", lookupId);
        lookup.put("partner_id", "GMEPAY");
        lookup.put("content_type", "TRANSACTION_REQUEST_ID");
        lookup.put("transaction_id", requestId);
        lookup.put("signature", sign(canonical(lookupId, "GMEPAY", requestId)));
        assertThat(postJson("/service/transfer/info", lookup)
                .path("data").path("status").asText()).isEqualTo("PROCESSING");
    }

    // T09 — bank-list + decode-qr shapes.
    @Test
    void t09_bankListAndDecodeQr() throws Exception {
        mvc.perform(get("/transfer-bank/bank-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.banks[0].bank_no").value("VIETCOMBANK"));

        String requestId = newRequestId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("partner_id", "GMEPAY");
        body.put("str_qr", "VIETQR|BIDV|0011223344556|50000|LE VAN CUONG");
        body.put("signature", sign(canonical(requestId, "GMEPAY"))); // only 2 fields signed per spec
        JsonNode decoded = postJson("/service/v2/decode-qr", body).path("data");
        assertThat(decoded.path("type").asText()).isEqualTo("VIETQR");
        assertThat(decoded.path("bank_no").asText()).isEqualTo("BIDV");
        assertThat(decoded.path("account_number").asText()).isEqualTo("0011223344556");
        assertThat(decoded.path("amount").asLong()).isEqualTo(50_000L);
        assertThat(decoded.path("account_name").asText()).isEqualTo("LE VAN CUONG");
    }
}
