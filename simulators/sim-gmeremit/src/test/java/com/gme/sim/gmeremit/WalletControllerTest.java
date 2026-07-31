package com.gme.sim.gmeremit;

import com.gme.sim.gmeremit.model.WalletStore;
import com.gme.sim.gmeremit.service.HubClient;
import com.gme.sim.gmeremit.service.NepalQrClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller-level tests for the wallet simulator.
 *
 * <p>HubClient is mocked so no real HTTP is made.
 *
 * Tests:
 *  1. GET /users returns all seeded users with balances.
 *  2. Insufficient funds -> 422 INSUFFICIENT_FUNDS, hub is NEVER called.
 *  3. Approved payment -> 201, balance debited by chargedKrw, receipt present.
 *  4. Declined by hub -> 422 DECLINED, balance unchanged.
 *  5. GET /users/{id}/transactions reflects committed payments.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class WalletControllerTest {

    @Autowired MockMvc mvc;
    @Autowired WalletStore   store;
    @MockBean  HubClient     hub;
    @MockBean  NepalQrClient nepalQr;

    private static final String QR = "00020101021226370016A000000642013601011234567890520412345303410540550005802KR5910CoffeeShop6002Seoul63041234";

    // A Fonepay (Nepal) QR — note "fonepay.com" and "5802NP".
    private static final String NEPAL_QR =
            "00020101021126350011fonepay.com071640897200000017835204541253035245802NP5914SudanMerchant6015AathraiTriveni6206070231630421f2";

    @BeforeEach
    void resetStore() {
        // Re-seed the store before each test so balances are fresh (500,000 each)
        var users = store.allUsers();
        // Drain transactions via reflection is tricky; instead we replace the field via
        // a package-private reset exposed by WalletStore for tests.
        store.resetForTest();
    }

    // ---- 1. Users seeded ----

    @Test
    void usersEndpointReturnsSeedData() throws Exception {
        mvc.perform(get("/v1/gmeremit/users"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()", equalTo(3)))
           .andExpect(jsonPath("$[0].userId",     equalTo("user-001")))
           .andExpect(jsonPath("$[0].name",       equalTo("Alice Kim")))
           .andExpect(jsonPath("$[0].balanceKrw", equalTo("500000")));
    }

    // ---- 2. Insufficient funds -> 422, hub never called ----

    @Test
    void insufficientFundsReturns422WithoutCallingHub() throws Exception {
        mvc.perform(post("/v1/gmeremit/users/user-001/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"qrPayload\":\"" + QR + "\",\"amountKrw\":\"600000\"}"))
           .andExpect(status().isUnprocessableEntity())
           .andExpect(jsonPath("$.status",        equalTo("DECLINED")))
           .andExpect(jsonPath("$.declineReason", equalTo("INSUFFICIENT_FUNDS")));

        verify(hub, never()).pay(anyString(), anyString(), anyString(), anyString());
    }

    // ---- 3. Approved payment debits balance ----

    @Test
    void approvedPaymentDebitsBalance() throws Exception {
        given(hub.pay(anyString(), anyString(), anyString(), anyString()))
            .willReturn(new HubClient.HubPayResult(
                    true, false,
                    "TXN-AABB1122CCDD",
                    "Coffee Shop",
                    "KRW", "50000",
                    "50000", "500", "50500",
                    "2026-06-13T11:23:45+09:00",
                    null
            ));

        mvc.perform(post("/v1/gmeremit/users/user-002/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"qrPayload\":\"" + QR + "\",\"amountKrw\":\"50000\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.status",                    equalTo("APPROVED")))
           .andExpect(jsonPath("$.receipt.merchantName",      equalTo("Coffee Shop")))
           .andExpect(jsonPath("$.receipt.currency",          equalTo("KRW")))
           .andExpect(jsonPath("$.receipt.payAmountKrw",      equalTo("50000")))
           .andExpect(jsonPath("$.receipt.feeKrw",            equalTo("500")))
           .andExpect(jsonPath("$.receipt.chargedKrw",        equalTo("50500")))
           .andExpect(jsonPath("$.receipt.schemeTxnRef",      equalTo("TXN-AABB1122CCDD")))
           .andExpect(jsonPath("$.newBalanceKrw",             equalTo("449500")));
    }

    // ---- 4. Declined by hub -> no debit ----

    @Test
    void declinedByHubNoDebit() throws Exception {
        given(hub.pay(anyString(), anyString(), anyString(), anyString()))
            .willReturn(new HubClient.HubPayResult(
                    false, false,
                    null, "Coffee Shop",
                    null, null, null, null, null, null,
                    "MERCHANT_INACTIVE"
            ));

        mvc.perform(post("/v1/gmeremit/users/user-003/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"qrPayload\":\"" + QR + "\",\"amountKrw\":\"10000\"}"))
           .andExpect(status().isUnprocessableEntity())
           .andExpect(jsonPath("$.status",        equalTo("DECLINED")))
           .andExpect(jsonPath("$.declineReason", equalTo("MERCHANT_INACTIVE")));

        // Balance must be unchanged at 500000
        mvc.perform(get("/v1/gmeremit/users"))
           .andExpect(jsonPath("$[2].balanceKrw", equalTo("500000")));
    }

    // ---- 5. Transactions list reflects approved payment ----

    @Test
    void transactionsReflectApprovedPayment() throws Exception {
        given(hub.pay(anyString(), anyString(), anyString(), anyString()))
            .willReturn(new HubClient.HubPayResult(
                    true, false,
                    "TXN-TEST99",
                    "Noodle House",
                    "KRW", "20000",
                    "20000", "500", "20500",
                    "2026-06-13T12:00:00+09:00",
                    null
            ));

        // Make a payment
        mvc.perform(post("/v1/gmeremit/users/user-001/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"qrPayload\":\"" + QR + "\",\"amountKrw\":\"20000\"}"))
           .andExpect(status().isCreated());

        // Check transactions
        mvc.perform(get("/v1/gmeremit/users/user-001/transactions"))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()",          equalTo(1)))
           .andExpect(jsonPath("$[0].schemeTxnRef",   equalTo("TXN-TEST99")))
           .andExpect(jsonPath("$[0].merchantName",   equalTo("Noodle House")))
           .andExpect(jsonPath("$[0].currency",       equalTo("KRW")))
           .andExpect(jsonPath("$[0].chargedKrw",     equalTo("20500")));

        // Domestic path never touches the Nepal sim.
        verify(nepalQr, never()).decode(anyString());
    }

    // ---- 6. Nepal (Fonepay) QR: detected cross-border, decoded to a REAL merchant + NPR ----

    @Test
    void nepalQrScanShowsRealMerchantAndNpr() throws Exception {
        // GMEPay+ is the authority for the corridor + currency: the wallet displays what the hub says.
        given(hub.classify(anyString())).willReturn(new HubClient.HubClassification(
                true, "fonepay.com", "NP", "NPR", "MPM", "NEPAL"));
        given(nepalQr.decode(anyString())).willReturn(new NepalQrClient.NepalParse(
                "EMVCo", "static", "Sudan Merchant", "Aathrai Triveni", "NP",
                "NPR", null   // static QR → user enters the NPR amount
        ));

        mvc.perform(post("/v1/gmeremit/scan")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"qrPayload\":\"" + NEPAL_QR + "\"}"))
           .andExpect(status().isOk())
           // Currency/network/country/scheme come from GMEPay+, not hardcoded by the wallet.
           .andExpect(jsonPath("$.currency",     equalTo("NPR")))
           .andExpect(jsonPath("$.network",      equalTo("fonepay.com")))
           .andExpect(jsonPath("$.country",      equalTo("NP")))
           .andExpect(jsonPath("$.scheme",       equalTo("NEPAL")))
           .andExpect(jsonPath("$.detectedBy",   equalTo("GMEPAY+")))
           .andExpect(jsonPath("$.supported",    equalTo(true)))
           // Merchant details still come from the partner decode.
           .andExpect(jsonPath("$.merchantName", equalTo("Sudan Merchant")))
           .andExpect(jsonPath("$.merchantCity", equalTo("Aathrai Triveni")))
           .andExpect(jsonPath("$.mode",         equalTo("static")));

        // The domestic scheme decode must NOT be used for a Nepal QR.
        verify(hub, never()).decodeQr(anyString());
    }

    // ---- 7. Nepal pay: NPR amount + currency=NPR sent to hub; KRW debit from the mock rate ----

    @Test
    void nepalPaySendsNprAmountAndCurrencyAndDebitsKrw() throws Exception {
        // Hub approves; returns NPR currency + amount but no KRW figures (hub side still being wired),
        // so the wallet computes the KRW debit from the sim FX rate.
        given(hub.pay(anyString(), anyString(), anyString(), anyString()))
            .willReturn(new HubClient.HubPayResult(
                    true, false, "NPTXN-1", "Sudan Merchant",
                    "NPR", "1000",
                    null, null, null,
                    "2026-07-02T15:00:00+09:00", null
            ));

        // user-002 has 500,000 KRW. NPR 1000 × 1.05 × 1.02 = 1071 KRW + 500 fee = 1571 debit.
        mvc.perform(post("/v1/gmeremit/users/user-002/pay")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"qrPayload\":\"" + NEPAL_QR + "\",\"amount\":\"1000\"}"))
           .andExpect(status().isCreated())
           .andExpect(jsonPath("$.status",               equalTo("APPROVED")))
           .andExpect(jsonPath("$.receipt.currency",     equalTo("NPR")))
           .andExpect(jsonPath("$.receipt.payAmount",    equalTo("1000")))
           .andExpect(jsonPath("$.receipt.payAmountKrw", equalTo("1071")))
           .andExpect(jsonPath("$.receipt.feeKrw",       equalTo("500")))
           .andExpect(jsonPath("$.receipt.chargedKrw",   equalTo("1571")))
           .andExpect(jsonPath("$.newBalanceKrw",        equalTo("498429")));

        // Hub was called with currency=NPR and the NPR amount (not KRW).
        verify(hub).pay(eq(NEPAL_QR), eq("NPR"), eq("1000"), eq("user-002"));
    }
}
