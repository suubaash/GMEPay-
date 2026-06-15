package com.gme.sim.scheme;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.sim.scheme.config.SchemeProfile;
import com.gme.sim.scheme.dto.*;
import com.gme.sim.scheme.emvco.Crc16;
import com.gme.sim.scheme.emvco.EmvcoQrEncoder;
import com.gme.sim.scheme.model.MerchantRecord;
import com.gme.sim.scheme.model.SchemeStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration-style MockMvc tests for the scheme simulator.
 *
 * Tests:
 *  T01 – Dynamic QR amount-mismatch → 422 AMOUNT_MISMATCH
 *  T02 – Bad CRC in qrPayload → 400 INVALID_QR
 *  T03 – CPM token expiry → 409 TOKEN_EXPIRED
 *  T04 – Full authorize → commit happy path
 *  T05 – Refund after capture
 *  T06 – Illegal FSM transition: commit already-captured → 409
 *  T07 – Profile switch: ZEROPAY uses KRW (numeric 410) in payload
 *  T08 – Static QR contains tag 01 = "11"
 *  T09 – Dynamic QR contains tag 01 = "12"
 *  T10 – Unknown merchant → 404
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gmepay.sim.scheme.profile=KHQR")
@AutoConfigureMockMvc
class SchemeControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SchemeStore store;

    private static final String MID = "KHQR-M001";  // seeded on boot

    @BeforeEach
    void verifyDemoMerchantSeeded() {
        // Seed is done by @PostConstruct; just assert it exists
        assert store.findMerchant(MID).isPresent() : "Demo merchant must be seeded";
    }

    // T01 – Dynamic amount mismatch
    @Test
    void t01_dynamicAmountMismatch_returns422() throws Exception {
        // Build a dynamic QR with amount 100
        MerchantRecord m = store.findMerchant(MID).orElseThrow();
        String payload = EmvcoQrEncoder.buildDynamic(m, SchemeProfile.KHQR, new BigDecimal("100"));

        AuthorizeRequest req = new AuthorizeRequest(
                "MPM_DYNAMIC", payload, null,
                new BigDecimal("999"), "KHR", "PAYER-01");

        mvc.perform(post("/v1/scheme/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("AMOUNT_MISMATCH"));
    }

    // T02 – Bad CRC
    @Test
    void t02_badCrc_returns400() throws Exception {
        MerchantRecord m = store.findMerchant(MID).orElseThrow();
        String payload = EmvcoQrEncoder.buildStatic(m, SchemeProfile.KHQR);
        // Corrupt last char of CRC
        String tampered = payload.substring(0, payload.length() - 1) + "X";

        AuthorizeRequest req = new AuthorizeRequest(
                "MPM_STATIC", tampered, null,
                new BigDecimal("500"), "KHR", "PAYER-02");

        mvc.perform(post("/v1/scheme/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_QR"));
    }

    // T03 – CPM token expired
    @Test
    void t03_cpmTokenExpired_returns409() throws Exception {
        // First create a token
        CpmTokenRequest tokenReq = new CpmTokenRequest("CUST-99", "FUND-99");
        String tokenResp = mvc.perform(post("/v1/scheme/cpm/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(tokenReq)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String token = mapper.readTree(tokenResp).get("cpmToken").asText();

        // Manually expire it in the store
        com.gme.sim.scheme.model.CpmTokenRecord rec = store.findCpmToken(token).orElseThrow();
        store.saveCpmToken(new com.gme.sim.scheme.model.CpmTokenRecord(
                token, rec.customerId(), rec.fundingRef(),
                Instant.now().minusSeconds(120)));   // expired

        AuthorizeRequest req = new AuthorizeRequest(
                "CPM", null, token,
                new BigDecimal("1000"), "KHR", "PAYER-03");

        mvc.perform(post("/v1/scheme/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    // T04 – Happy path: authorize then commit
    @Test
    void t04_authorizeAndCommit_happyPath() throws Exception {
        MerchantRecord m = store.findMerchant(MID).orElseThrow();
        String payload = EmvcoQrEncoder.buildStatic(m, SchemeProfile.KHQR);

        AuthorizeRequest authReq = new AuthorizeRequest(
                "MPM_STATIC", payload, null,
                new BigDecimal("8000"), "KHR", "PAYER-04");

        String authResp = mvc.perform(post("/v1/scheme/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(authReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andReturn().getResponse().getContentAsString();

        String authId = mapper.readTree(authResp).get("authId").asText();

        mvc.perform(post("/v1/scheme/payments/{authId}/commit", authId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.schemeTxnRef").isNotEmpty());
    }

    // T05 – Refund after capture
    @Test
    void t05_refundAfterCapture() throws Exception {
        MerchantRecord m = store.findMerchant(MID).orElseThrow();
        String payload = EmvcoQrEncoder.buildStatic(m, SchemeProfile.KHQR);

        String authResp = mvc.perform(post("/v1/scheme/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new AuthorizeRequest(
                                "MPM_STATIC", payload, null,
                                new BigDecimal("3000"), "KHR", "PAYER-05"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String authId = mapper.readTree(authResp).get("authId").asText();

        mvc.perform(post("/v1/scheme/payments/{authId}/commit", authId))
                .andExpect(status().isOk());

        mvc.perform(post("/v1/scheme/payments/{authId}/refund", authId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new RefundRequest(new BigDecimal("3000")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundId").isNotEmpty());
    }

    // T06 – Illegal FSM: commit twice → 409
    @Test
    void t06_illegalFsmTransition_commitTwice_returns409() throws Exception {
        MerchantRecord m = store.findMerchant(MID).orElseThrow();
        String payload = EmvcoQrEncoder.buildStatic(m, SchemeProfile.KHQR);

        String authResp = mvc.perform(post("/v1/scheme/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new AuthorizeRequest(
                                "MPM_STATIC", payload, null,
                                new BigDecimal("200"), "KHR", "PAYER-06"))))
                .andReturn().getResponse().getContentAsString();
        String authId = mapper.readTree(authResp).get("authId").asText();

        mvc.perform(post("/v1/scheme/payments/{authId}/commit", authId))
                .andExpect(status().isOk());

        mvc.perform(post("/v1/scheme/payments/{authId}/commit", authId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ILLEGAL_TRANSITION"));
    }

    // T07 – Profile switch: ZEROPAY payload contains numeric currency 410
    @Test
    void t07_zeropayProfile_currencyCode410() {
        MerchantRecord m = new MerchantRecord("ZP-M001", "Seoul Noodle House", "Seoul", "5812");
        String payload = EmvcoQrEncoder.buildStatic(m, SchemeProfile.ZEROPAY);
        // Tag 53, length 03, value "410" → wire encoding "5303410"
        // (2-char tag "53" + 2-char length "03" + value "410")
        assertTrue(payload.contains("5303410"),
                "ZEROPAY payload must embed numeric currency code 410; payload=" + payload);
    }

    // T08 – Static QR tag 01 = "11"
    @Test
    void t08_staticQr_endpointReturnsMode_MPM_STATIC() throws Exception {
        mvc.perform(post("/v1/scheme/qr/static")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":\"" + MID + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("MPM_STATIC"))
                .andExpect(jsonPath("$.qrPayload", containsString("010211")));
    }

    // T09 – Dynamic QR tag 01 = "12"
    @Test
    void t09_dynamicQr_endpointReturnsMode_MPM_DYNAMIC() throws Exception {
        String body = "{\"merchantId\":\"" + MID + "\",\"amount\":\"5000\",\"currency\":\"KHR\"}";
        mvc.perform(post("/v1/scheme/qr/dynamic")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("MPM_DYNAMIC"))
                .andExpect(jsonPath("$.qrPayload", containsString("010212")));
    }

    // T10 – Unknown merchant → 404
    @Test
    void t10_unknownMerchant_staticQr_returns404() throws Exception {
        mvc.perform(post("/v1/scheme/qr/static")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":\"NO-SUCH-MERCHANT\"}"))
                .andExpect(status().isNotFound());
    }
}
