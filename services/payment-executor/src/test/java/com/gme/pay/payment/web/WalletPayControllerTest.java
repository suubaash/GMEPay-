package com.gme.pay.payment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.payment.domain.GmeremitPaymentService;
import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import com.gme.pay.payment.domain.SchemeDeclinedException;
import com.gme.pay.payment.domain.SendmnPaymentService;
import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice test for {@link WalletPayController}.
 *
 * <p>Loads only the web layer; service beans are mocked via Mockito.
 * No Postgres / Kafka / downstream services required — runs on H2 + MockMvc.
 *
 * <p>Tests covered:
 * <ol>
 *   <li>GMEREMIT happy path — APPROVED: 201 Created, correct KRW fields, schemeTxnRef present.
 *   <li>DEACTIVATED merchant — DECLINED: 422, status DECLINED, declineReason MERCHANT_INACTIVE.
 *   <li>Missing required field — 400 Bad Request.
 *   <li>SENDMN happy path — 201 Created, FX fields present.
 *   <li>Unknown partner — 400 Bad Request.
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

    @MockBean
    private SendmnPaymentService sendmnPaymentService;

    @MockBean
    private SchemeClient schemeClient;

    @MockBean
    private TransactionClient transactionClient;

    @MockBean
    private RevenueLedgerClient revenueLedgerClient;

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

    // ---- Test 4: SENDMN happy path ----

    @Test
    @DisplayName("POST /v1/pay SENDMN — APPROVED: 201 with FX fields")
    void walletPay_sendmn_approved() throws Exception {
        WalletResult fxResult = WalletResult.approvedFx(
                "ZP_TXN_MNT_001",
                "MNT Merchant",
                new BigDecimal("10000"),
                new BigDecimal("500"),
                new BigDecimal("10500"),
                "2026-06-15T12:00:00+09:00",
                new BigDecimal("3.430000"),
                new BigDecimal("34300")
        );
        when(sendmnPaymentService.pay(eq("ZPQR_MNT"), eq(new BigDecimal("10000")),
                eq("user-mn-001"), anyLong()))
                .thenReturn(fxResult);

        String body = """
                {
                  "qrPayload": "ZPQR_MNT",
                  "amountKrw": "10000",
                  "partner": "SENDMN",
                  "userRef": "user-mn-001"
                }
                """;

        mockMvc.perform(post("/v1/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.schemeTxnRef", is("ZP_TXN_MNT_001")))
                .andExpect(jsonPath("$.fxApplied", is(true)))
                .andExpect(jsonPath("$.fxRate", is("3.430000")))
                .andExpect(jsonPath("$.payAmountMnt", is("34300")));
    }

    // ---- Test 5: Unknown partner → 400 ----

    @Test
    @DisplayName("POST /v1/pay — unknown partner: 400 Bad Request")
    void walletPay_unknownPartner_400() throws Exception {
        String body = """
                {
                  "qrPayload": "ZPQR0001",
                  "amountKrw": "10000",
                  "partner": "UNKNOWN_CORP",
                  "userRef": "user-007"
                }
                """;

        mockMvc.perform(post("/v1/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ---- Test 6: Refund happy path ----

    @Test
    @DisplayName("POST /v1/pay/{schemeTxnRef}/refund — REFUNDED: 200 with status=REFUNDED")
    void walletPay_refund_happyPath() throws Exception {
        doNothing().when(schemeClient).cancelPayment(eq("AUTH-CPM-001"), anyString());

        String body = """
                {
                  "authId": "AUTH-CPM-001",
                  "reason": "CUSTOMER_REQUEST"
                }
                """;

        mockMvc.perform(post("/v1/pay/TXN-AABB1122/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REFUNDED")))
                .andExpect(jsonPath("$.schemeTxnRef", is("TXN-AABB1122")))
                .andExpect(jsonPath("$.authId", is("AUTH-CPM-001")));
    }

    // ---- Test 7: Refund — scheme declines (already refunded) → 422 ----

    @Test
    @DisplayName("POST /v1/pay/{schemeTxnRef}/refund — scheme decline: 422 with status=FAILED")
    void walletPay_refund_schemeDeclines_422() throws Exception {
        doThrow(new SchemeDeclinedException("ALREADY_REFUNDED", "Transaction already refunded"))
                .when(schemeClient).cancelPayment(anyString(), anyString());

        String body = """
                {
                  "authId": "AUTH-CPM-USED",
                  "reason": "CUSTOMER_REQUEST"
                }
                """;

        mockMvc.perform(post("/v1/pay/TXN-ALREADY-DONE/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status", is("FAILED")));
    }
}
