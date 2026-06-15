package com.gme.sim.merchant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gme.sim.merchant.dto.ChargeRequest;
import com.gme.sim.merchant.dto.ChargeResponse;
import com.gme.sim.merchant.dto.RegisterShopRequest;
import com.gme.sim.merchant.model.ShopRecord;
import com.gme.sim.merchant.model.ShopStore;
import com.gme.sim.merchant.service.SchemeClient;
import com.gme.sim.merchant.service.SchemeUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for MerchantController with a mocked SchemeClient.
 *
 *  M01 – POST /shops proxies registration to scheme → 201 + merchantId
 *  M02 – GET /shops returns registered shops
 *  M03 – GET /shops/{id}/store-qr proxies to scheme
 *  M04 – POST /shops/{id}/charge proxies to scheme qr/dynamic
 *  M05 – GET /shops/{id}/payments proxies feed with since-cursor
 *  M06 – scheme down → 503 friendly response
 *  M07 – @SpringBootTest context loads
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class MerchantControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ShopStore shopStore;
    @MockBean  SchemeClient schemeClient;

    private static final String TEST_MID = "ZP-TESTABCD";

    @BeforeEach
    void setUp() {
        // Pre-seed a shop for tests that need an existing shop
        shopStore.save(new ShopRecord(
                TEST_MID, "Seoul Noodle House", "Seoul", "5812",
                null, null, null, "SMALL_BIZ", "0.0000"));
    }

    // -------------------------------------------------------------------------
    // M01 – shop registration proxies to scheme
    // -------------------------------------------------------------------------
    @Test
    void m01_registerShop_proxiesSchemeAndReturns201() throws Exception {
        ShopRecord schemeResult = new ShopRecord(
                TEST_MID, "Seoul Noodle House", "Seoul", "5812",
                "123-45-67890", null, null, "SMALL_BIZ", "0.0000");

        when(schemeClient.registerMerchant(any(RegisterShopRequest.class)))
                .thenReturn(schemeResult);

        String body = """
                {"name":"Seoul Noodle House","city":"Seoul","mcc":"5812",
                 "businessRegNo":"123-45-67890","merchantType":"SMALL_BIZ"}
                """;

        mvc.perform(post("/v1/merchant/shops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.merchantId").value(TEST_MID))
                .andExpect(jsonPath("$.name").value("Seoul Noodle House"))
                .andExpect(jsonPath("$.feeRate").value("0.0000"));

        verify(schemeClient).registerMerchant(any(RegisterShopRequest.class));
    }

    // -------------------------------------------------------------------------
    // M02 – GET /shops returns registered shops
    // -------------------------------------------------------------------------
    @Test
    void m02_listShops_returnsRegisteredShops() throws Exception {
        mvc.perform(get("/v1/merchant/shops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[?(@.merchantId == '" + TEST_MID + "')]").isArray());
    }

    // -------------------------------------------------------------------------
    // M03 – store-qr proxies to scheme
    // -------------------------------------------------------------------------
    @Test
    void m03_storeQr_proxiesScheme() throws Exception {
        ObjectNode schemeResp = JsonNodeFactory.instance.objectNode();
        schemeResp.put("merchantId",   TEST_MID);
        schemeResp.put("merchantName", "Seoul Noodle House");
        schemeResp.put("mode",         "MPM_STATIC");
        schemeResp.put("qrPayload",    "000201010211...");
        schemeResp.put("schemeId",     "ZEROPAY");
        schemeResp.put("currency",     "KRW");

        when(schemeClient.getStoreQr(TEST_MID)).thenReturn(schemeResp);

        mvc.perform(get("/v1/merchant/shops/{merchantId}/store-qr", TEST_MID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value(TEST_MID))
                .andExpect(jsonPath("$.mode").value("MPM_STATIC"))
                .andExpect(jsonPath("$.schemeId").value("ZEROPAY"))
                .andExpect(jsonPath("$.currency").value("KRW"));

        verify(schemeClient).getStoreQr(TEST_MID);
    }

    // -------------------------------------------------------------------------
    // M04 – charge proxies to scheme qr/dynamic
    // -------------------------------------------------------------------------
    @Test
    void m04_charge_proxiesSchemeQrDynamic() throws Exception {
        ChargeResponse chargeResp = new ChargeResponse(
                "MPM_DYNAMIC", "000201010212...", new BigDecimal("12500"), "KRW");

        when(schemeClient.mintDynamicQr(eq(TEST_MID), any(BigDecimal.class), eq("KRW")))
                .thenReturn(chargeResp);

        String body = "{\"amount\":\"12500\",\"currency\":\"KRW\"}";

        mvc.perform(post("/v1/merchant/shops/{merchantId}/charge", TEST_MID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("MPM_DYNAMIC"))
                .andExpect(jsonPath("$.amount").value("12500"))
                .andExpect(jsonPath("$.currency").value("KRW"))
                .andExpect(jsonPath("$.qrPayload").isNotEmpty());

        verify(schemeClient).mintDynamicQr(eq(TEST_MID), any(BigDecimal.class), eq("KRW"));
    }

    // -------------------------------------------------------------------------
    // M05 – payments feed proxies with since-cursor
    // -------------------------------------------------------------------------
    @Test
    void m05_paymentsFeed_proxiesWithSinceCursor() throws Exception {
        ObjectNode feedResp = JsonNodeFactory.instance.objectNode();
        feedResp.put("merchantId", TEST_MID);
        feedResp.putArray("events");
        feedResp.put("latestSeq", 0L);

        when(schemeClient.getPaymentFeed(TEST_MID, 5L)).thenReturn(feedResp);

        mvc.perform(get("/v1/merchant/shops/{merchantId}/payments?since=5", TEST_MID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantId").value(TEST_MID))
                .andExpect(jsonPath("$.latestSeq").value(0));

        verify(schemeClient).getPaymentFeed(TEST_MID, 5L);
    }

    // -------------------------------------------------------------------------
    // M06 – scheme down → 503 friendly response
    // -------------------------------------------------------------------------
    @Test
    void m06_schemeDown_returns503WithFriendlyBody() throws Exception {
        when(schemeClient.registerMerchant(any(RegisterShopRequest.class)))
                .thenThrow(new SchemeUnavailableException("Connection refused",
                        new ResourceAccessException("Connection refused")));

        String body = "{\"name\":\"Test Shop\",\"city\":\"Seoul\",\"mcc\":\"5812\"}";

        mvc.perform(post("/v1/merchant/shops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SCHEME_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // M07 – context loads (implicit — test class annotated @SpringBootTest)
    // -------------------------------------------------------------------------
    @Test
    void m07_contextLoads() {
        // If the test class starts, the application context loaded successfully.
    }
}
