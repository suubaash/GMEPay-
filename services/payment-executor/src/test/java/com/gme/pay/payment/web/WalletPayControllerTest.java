package com.gme.pay.payment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.payment.domain.GmeremitPaymentService;
import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for {@link WalletPayController}.
 *
 * <p>Loads only the web layer; {@link GmeremitPaymentService} is mocked via Mockito.
 * No Postgres / Kafka / downstream services required — runs on H2 + MockMvc.
 *
 * <p>Tests covered:
 * <ol>
 *   <li>Happy path — APPROVED: 201 Created, correct KRW fields, schemeTxnRef present.
 *   <li>DEACTIVATED merchant — DECLINED: 422, status DECLINED, declineReason MERCHANT_INACTIVE.
 *   <li>Missing required field — 400 Bad Request.
 * </ol>
 */
@WebMvcTest(WalletPayController.class)
class WalletPayControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private GmeremitPaymentService gmeremitPaymentService;

    // ---- Test 1: APPROVED happy path ----

    @Test
    @DisplayName("POST /v1/pay — APPROVED: 201 with schemeTxnRef, KRW fields, fee=500")
    void walletPay_approvedHappyPath() throws Exception {
        WalletResult approved = WalletResult.approved(
                "TXN-AABB1122",
                "Coffee Shop",
                new BigDecimal("50000"),
                new BigDecimal("500"),
                new BigDecimal("50500"),
                "2026-06-13T11:23:45+09:00"
        );
        when(gmeremitPaymentService.pay(eq("ZPQR0001"), eq(new BigDecimal("50000")), eq("user-007")))
                .thenReturn(approved);

        String body = """
                {
                  "qrPayload": "ZPQR0001",
                  "amountKrw": "50000",
                  "partner": "GMEREMIT",
                  "userRef": "user-007"
                }
                """;

        mockMvc.perform(post("/v1/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.schemeTxnRef", is("TXN-AABB1122")))
                .andExpect(jsonPath("$.merchantName", is("Coffee Shop")))
                .andExpect(jsonPath("$.payAmountKrw", is("50000")))
                .andExpect(jsonPath("$.feeKrw", is("500")))
                .andExpect(jsonPath("$.chargedKrw", is("50500")))
                .andExpect(jsonPath("$.committedAt", is("2026-06-13T11:23:45+09:00")));
    }

    // ---- Test 2: DEACTIVATED merchant → DECLINED ----

    @Test
    @DisplayName("POST /v1/pay — DEACTIVATED merchant: 422 with status=DECLINED, reason=MERCHANT_INACTIVE")
    void walletPay_deactivatedMerchantDeclined() throws Exception {
        WalletResult declined = WalletResult.declined("Closed Merchant", "MERCHANT_INACTIVE");
        when(gmeremitPaymentService.pay(eq("ZPQR_INACTIVE"), any(BigDecimal.class), any()))
                .thenReturn(declined);

        String body = """
                {
                  "qrPayload": "ZPQR_INACTIVE",
                  "amountKrw": "10000",
                  "partner": "GMEREMIT",
                  "userRef": "user-007"
                }
                """;

        mockMvc.perform(post("/v1/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status", is("DECLINED")))
                .andExpect(jsonPath("$.merchantName", is("Closed Merchant")))
                .andExpect(jsonPath("$.declineReason", is("MERCHANT_INACTIVE")));
    }

    // ---- Test 3: Missing required field → 400 ----

    @Test
    @DisplayName("POST /v1/pay — missing amountKrw: 400 Bad Request")
    void walletPay_missingField_400() throws Exception {
        String body = """
                {
                  "qrPayload": "ZPQR0001",
                  "partner": "GMEREMIT",
                  "userRef": "user-007"
                }
                """;

        mockMvc.perform(post("/v1/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
