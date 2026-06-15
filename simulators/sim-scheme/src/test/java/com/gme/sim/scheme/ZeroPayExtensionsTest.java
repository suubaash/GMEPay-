package com.gme.sim.scheme;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.sim.scheme.dto.AuthorizeRequest;
import com.gme.sim.scheme.dto.RefundRequest;
import com.gme.sim.scheme.dto.RegisterMerchantRequest;
import com.gme.sim.scheme.emvco.EmvcoQrEncoder;
import com.gme.sim.scheme.config.SchemeProfile;
import com.gme.sim.scheme.model.MerchantRecord;
import com.gme.sim.scheme.model.MerchantType;
import com.gme.sim.scheme.model.SchemeStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * New tests for ZeroPay extensions:
 *
 *  N01 – store-qr happy path (seeded ZP-M001)
 *  N02 – store-qr 404 for unknown merchant
 *  N03 – payment feed empty for merchant with no payments
 *  N04 – feed shows APPROVED after authorize
 *  N05 – feed shows CAPTURED + schemeTxnRef after commit
 *  N06 – feed since-cursor filtering
 *  N07 – MerchantType → feeRate mapping (SMALL_BIZ=0.0000, GENERAL=0.0080)
 *  N08 – additive merchant fields round-trip via POST /merchants
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class ZeroPayExtensionsTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SchemeStore store;

    /** Seeded ZEROPAY merchant (default profile). */
    private static final String ZP_MID = "ZP-M001";

    // -------------------------------------------------------------------------
    // N01 – store-qr happy path
    // -------------------------------------------------------------------------
    @Test
    void n01_storeQr_happyPath() throws Exception {
        mvc.perform(get("/v1/scheme/merchants/{merchantId}/store-qr", ZP_MID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value(ZP_MID))
                .andExpect(jsonPath("$.merchantName").isNotEmpty())
                .andExpect(jsonPath("$.mode").value("MPM_STATIC"))
                .andExpect(jsonPath("$.qrPayload").isNotEmpty())
                .andExpect(jsonPath("$.schemeId").value("ZEROPAY"))
                .andExpect(jsonPath("$.currency").value("KRW"));
    }

    // -------------------------------------------------------------------------
    // N02 – store-qr 404 for unknown merchant
    // -------------------------------------------------------------------------
    @Test
    void n02_storeQr_unknownMerchant_returns404() throws Exception {
        mvc.perform(get("/v1/scheme/merchants/{merchantId}/store-qr", "NO-SUCH-MERCHANT"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    // N03 – payment feed empty for merchant with no payments
    // -------------------------------------------------------------------------
    @Test
    void n03_paymentFeed_emptyInitially() throws Exception {
        // Use a unique merchant id to guarantee no prior events
        String freshMid = "ZP-FEED-TEST-" + System.nanoTime();
        mvc.perform(get("/v1/scheme/merchants/{merchantId}/payments", freshMid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value(freshMid))
                .andExpect(jsonPath("$.events").isArray())
                .andExpect(jsonPath("$.events", hasSize(0)))
                .andExpect(jsonPath("$.latestSeq").value(0));
    }

    // -------------------------------------------------------------------------
    // N04 – feed shows APPROVED after authorize
    // -------------------------------------------------------------------------
    @Test
    void n04_feedShowsApproved_afterAuthorize() throws Exception {
        MerchantRecord m = store.findMerchant(ZP_MID).orElseThrow();
        String payload = EmvcoQrEncoder.buildStatic(m, SchemeProfile.ZEROPAY);

        AuthorizeRequest authReq = new AuthorizeRequest(
                "MPM_STATIC", payload, null,
                new BigDecimal("10000"), "KRW", "WALLET-N04");

        mvc.perform(post("/v1/scheme/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(authReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mvc.perform(get("/v1/scheme/merchants/{merchantId}/payments", ZP_MID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[?(@.status == 'APPROVED')]").isArray())
                .andExpect(jsonPath("$.events[?(@.status == 'APPROVED' && @.payerRef == 'WALLET-N04')]").isArray())
                .andExpect(jsonPath("$.latestSeq", greaterThanOrEqualTo(1)));
    }

    // -------------------------------------------------------------------------
    // N05 – feed shows CAPTURED + schemeTxnRef after commit
    // -------------------------------------------------------------------------
    @Test
    void n05_feedShowsCaptured_withSchemeTxnRef_afterCommit() throws Exception {
        // Create a dedicated merchant so events are isolated
        String mid = "ZP-N05-" + System.nanoTime();
        store.saveMerchant(new MerchantRecord(mid, "TestMerchantN05", "Seoul", "5999"));

        MerchantRecord m = store.findMerchant(mid).orElseThrow();
        String payload = EmvcoQrEncoder.buildStatic(m, SchemeProfile.ZEROPAY);

        String authResp = mvc.perform(post("/v1/scheme/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new AuthorizeRequest(
                                "MPM_STATIC", payload, null,
                                new BigDecimal("5000"), "KRW", "WALLET-N05"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String authId = mapper.readTree(authResp).get("authId").asText();

        mvc.perform(post("/v1/scheme/payments/{authId}/commit", authId))
                .andExpect(status().isOk());

        String feedResp = mvc.perform(get("/v1/scheme/merchants/{merchantId}/payments", mid))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode events = mapper.readTree(feedResp).get("events");
        // Should have APPROVED (seq=1) and CAPTURED (seq=2)
        assertEquals(2, events.size(), "Expected 2 events (APPROVED + CAPTURED)");

        JsonNode capturedEvent = events.get(1);
        assertEquals("CAPTURED", capturedEvent.get("status").asText());
        assertNotNull(capturedEvent.get("schemeTxnRef"),
                "schemeTxnRef must be present on CAPTURED event");
        assertFalse(capturedEvent.get("schemeTxnRef").isNull(),
                "schemeTxnRef must not be null on CAPTURED event");
        assertTrue(capturedEvent.get("schemeTxnRef").asText().startsWith("TXN-"));
    }

    // -------------------------------------------------------------------------
    // N06 – since-cursor filtering
    // -------------------------------------------------------------------------
    @Test
    void n06_feedSinceCursorFiltering() throws Exception {
        String mid = "ZP-N06-" + System.nanoTime();
        store.saveMerchant(new MerchantRecord(mid, "TestMerchantN06", "Busan", "5812"));

        MerchantRecord m = store.findMerchant(mid).orElseThrow();
        String payload = EmvcoQrEncoder.buildStatic(m, SchemeProfile.ZEROPAY);

        // Authorize + commit → 2 events (APPROVED seq=1, CAPTURED seq=2)
        String authResp = mvc.perform(post("/v1/scheme/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new AuthorizeRequest(
                                "MPM_STATIC", payload, null,
                                new BigDecimal("3000"), "KRW", "WALLET-N06"))))
                .andReturn().getResponse().getContentAsString();
        String authId = mapper.readTree(authResp).get("authId").asText();
        mvc.perform(post("/v1/scheme/payments/{authId}/commit", authId));

        // since=0 → 2 events
        String allResp = mvc.perform(get("/v1/scheme/merchants/{merchantId}/payments?since=0", mid))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode allEvents = mapper.readTree(allResp).get("events");
        assertTrue(allEvents.size() >= 2, "Expected at least 2 events with since=0");

        // since=1 → only events with seq > 1, i.e. CAPTURED (seq=2)
        String sinceResp = mvc.perform(get("/v1/scheme/merchants/{merchantId}/payments?since=1", mid))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode sinceEvents = mapper.readTree(sinceResp).get("events");
        for (JsonNode ev : sinceEvents) {
            assertTrue(ev.get("seq").asLong() > 1,
                    "All returned events must have seq > 1 when since=1");
        }
        // At least 1 event (the CAPTURED one)
        assertTrue(sinceEvents.size() >= 1);
        assertEquals("CAPTURED", sinceEvents.get(0).get("status").asText());
    }

    // -------------------------------------------------------------------------
    // N07 – MerchantType → feeRate mapping
    // -------------------------------------------------------------------------
    @Test
    void n07_merchantTypeFeeRateMapping() {
        MerchantType smallBiz = MerchantType.SMALL_BIZ;
        MerchantType general  = MerchantType.GENERAL;

        assertEquals(0, BigDecimal.ZERO.compareTo(smallBiz.feeRate),
                "SMALL_BIZ feeRate must be 0.0000");
        assertEquals(0, new BigDecimal("0.0080").compareTo(general.feeRate),
                "GENERAL feeRate must be 0.0080");

        // Record derivation
        MerchantRecord smRec = new MerchantRecord("X", "X", "X", "5999",
                null, null, null, null, null, MerchantType.SMALL_BIZ, null);
        assertEquals(0, BigDecimal.ZERO.compareTo(smRec.feeRate()),
                "MerchantRecord.feeRate() must equal SMALL_BIZ.feeRate");

        MerchantRecord genRec = new MerchantRecord("Y", "Y", "Y", "5999",
                null, null, null, null, null, MerchantType.GENERAL, null);
        assertEquals(0, new BigDecimal("0.0080").compareTo(genRec.feeRate()),
                "MerchantRecord.feeRate() must equal GENERAL.feeRate");
    }

    // -------------------------------------------------------------------------
    // N08 – additive merchant fields round-trip via POST /merchants
    // -------------------------------------------------------------------------
    @Test
    void n08_additiveMerchantFields_roundTrip() throws Exception {
        RegisterMerchantRequest req = new RegisterMerchantRequest(
                "ZP-ROUNDTRIP-001",
                "Gangnam Coffee",
                "Seoul",
                "5812",
                "123-45-67890",
                "KFTC-SUB-99999",
                "KFTC-001",
                "020",     // Woori Bank
                "1002-123-456789",
                MerchantType.SMALL_BIZ
        );

        String respJson = mvc.perform(post("/v1/scheme/merchants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.merchantId").value("ZP-ROUNDTRIP-001"))
                .andExpect(jsonPath("$.name").value("Gangnam Coffee"))
                .andExpect(jsonPath("$.city").value("Seoul"))
                .andExpect(jsonPath("$.mcc").value("5812"))
                .andExpect(jsonPath("$.businessRegNo").value("123-45-67890"))
                .andExpect(jsonPath("$.subMerchantId").value("KFTC-SUB-99999"))
                .andExpect(jsonPath("$.kftcInstitutionCode").value("KFTC-001"))
                .andExpect(jsonPath("$.settlementBankCode").value("020"))
                .andExpect(jsonPath("$.settlementAccountNo").value("1002-123-456789"))
                .andExpect(jsonPath("$.merchantType").value("SMALL_BIZ"))
                .andExpect(jsonPath("$.feeRate").value("0.0000"))
                .andReturn().getResponse().getContentAsString();

        // Verify the record is retrievable from the store with correct fields
        MerchantRecord stored = store.findMerchant("ZP-ROUNDTRIP-001").orElseThrow();
        assertEquals("123-45-67890", stored.businessRegNo());
        assertEquals(MerchantType.SMALL_BIZ, stored.merchantType());
        assertEquals(0, BigDecimal.ZERO.compareTo(stored.feeRate()));
    }
}
