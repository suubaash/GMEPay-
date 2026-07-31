package com.gme.pay.payment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.payment.domain.FailoverPaymentRouter;
import com.gme.pay.payment.domain.GmeremitPaymentService;
import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import com.gme.pay.payment.domain.OperationalGate;
import com.gme.pay.payment.domain.OperationalGateException;
import com.gme.pay.payment.domain.PaymentStatus;
import com.gme.pay.payment.domain.SchemeClosedException;
import com.gme.pay.payment.domain.SchemeDeclinedException;
import com.gme.pay.payment.domain.SchemeOperationNotSupportedException;
import com.gme.pay.payment.domain.SendmnPaymentService;
import com.gme.pay.payment.domain.TransactionLimitExceededException;
import com.gme.pay.payment.domain.WalletPartnerRef;
import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.persistence.IdempotencyRecordEntity;
import com.gme.pay.payment.persistence.IdempotencyRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private FailoverPaymentRouter failoverPaymentRouter;

    @MockBean
    private SchemeClient schemeClient;

    @MockBean
    private TransactionClient transactionClient;

    @MockBean
    private RevenueLedgerClient revenueLedgerClient;

    @MockBean
    private OperationalGate operationalGate;

    @MockBean
    private IdempotencyRecordRepository idempotencyRepository;

    // ---- Test 1: APPROVED happy path ----

    @Test
    @DisplayName("POST /v1/pay — APPROVED: 201 with schemeTxnRef, KRW fields, fee=500")
    void walletPay_approvedHappyPath() throws Exception {
        WalletResult approved = WalletResult.approved(
                "GMEREMIT-9001",
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
                .andExpect(jsonPath("$.txnRef", is("GMEREMIT-9001")))
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

    // ---- Test 4b: explicit partner=SENDMN + QPay QR stays on the FX corridor ----

    @Test
    @DisplayName("POST /v1/pay SENDMN + QPay(MN) QR — dispatches to SendmnPaymentService, NOT the failover router")
    void walletPay_sendmn_qpayQr_keepsFxCorridor() throws Exception {
        // A real QPay MPM QR (sim-sendmn shape: template 26 GUID A000000843000101, tag58=MN)
        // classifies to a KNOWN non-ZeroPay network. An explicit partner=SENDMN request must
        // still run the KRW→MNT FX corridor (fee + USD prefunding), not the failover MNT
        // pass-through — which would treat the KRW amount as MNT.
        String qpayQr = "00020101021126340016A00000084300010101101453767113"
                + "5204541153034965802MN5917NOMIN SUPERMARKET6304ABCD";
        WalletResult fxResult = WalletResult.approvedFx(
                "PMT-1001", "NOMIN SUPERMARKET",
                new BigDecimal("50000"), new BigDecimal("500"), new BigDecimal("50500"),
                "2026-07-27T12:00:00+09:00",
                new BigDecimal("2.450000"), new BigDecimal("122500"));
        when(sendmnPaymentService.pay(eq(qpayQr), eq(new BigDecimal("50000")),
                eq("user-mn-002"), anyLong()))
                .thenReturn(fxResult);

        String body = """
                {
                  "qrPayload": "%s",
                  "amountKrw": "50000",
                  "partner": "SENDMN",
                  "userRef": "user-mn-002"
                }
                """.formatted(qpayQr);

        mockMvc.perform(post("/v1/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.fxApplied", is(true)))
                .andExpect(jsonPath("$.payAmountMnt", is("122500")));

        verify(sendmnPaymentService).pay(eq(qpayQr), eq(new BigDecimal("50000")),
                eq("user-mn-002"), anyLong());
        verifyNoInteractions(failoverPaymentRouter);
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

    // ---- Test: non-ZeroPay (Fonepay) QR routes to the FailoverPaymentRouter ----

    @Test
    @DisplayName("POST /v1/pay — Fonepay QR routes to failover router: 201 APPROVED with schemeTxnRef")
    void walletPay_fonepayQr_routesToFailover() throws Exception {
        String fonepayQr = "00020101021126150011fonepay.com5802NP5910KINAUN PVT6304ABCD";

        WalletResult foApproved = WalletResult.approved(
                "NP-SCHEME-777",
                "NP-SCHEME-777",
                "Nepal",
                new BigDecimal("1000"),
                BigDecimal.ZERO,
                new BigDecimal("1000"),
                "2026-07-01T10:00:00+09:00");
        // partner is GMEREMIT (the wallet's issuing partner) — the QR's network decides routing.
        // No `currency` in the body → payCurrency() defaults to KRW (back-compat).
        // T4-2: the controller now threads the limit subject (the wallet partner) to the router.
        when(failoverPaymentRouter.pay(eq(fonepayQr), eq(new BigDecimal("1000")), eq("user-np-1"),
                anyString(), eq("KRW"), any(WalletPartnerRef.class)))
                .thenReturn(foApproved);

        String body = """
                {
                  "qrPayload": "%s",
                  "amountKrw": "1000",
                  "partner": "GMEREMIT",
                  "userRef": "user-np-1"
                }
                """.formatted(fonepayQr);

        mockMvc.perform(post("/v1/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.schemeTxnRef", is("NP-SCHEME-777")));

        // The ZeroPay domestic path must NOT be touched for a non-ZeroPay QR.
        verifyNoInteractions(gmeremitPaymentService);
    }

    // ---- Test: Fonepay QR + currency=NPR → failover router receives NPR amount, response carries NPR ----

    @Test
    @DisplayName("POST /v1/pay — Fonepay QR + currency=NPR: routes NPR amount to failover, response carries NPR")
    void walletPay_fonepayQr_currencyNpr_executedInNpr() throws Exception {
        String fonepayQr = "00020101021126150011fonepay.com5802NP5910KINAUN PVT6304ABCD";

        // Approved in NPR (no fee, no FX) — the wallet already sent the amount in NPR.
        WalletResult nprApproved = WalletResult.approvedInCurrency(
                "NEPAL-abc",
                "NP-SCHEME-999",
                "Nepal Merchant",
                new BigDecimal("1300"),
                BigDecimal.ZERO,
                new BigDecimal("1300"),
                "2026-07-02T10:00:00+09:00",
                "NPR");
        when(failoverPaymentRouter.pay(eq(fonepayQr), eq(new BigDecimal("1300")),
                eq("user-np-2"), anyString(), eq("NPR"), any(WalletPartnerRef.class)))
                .thenReturn(nprApproved);

        String body = """
                {
                  "qrPayload": "%s",
                  "amountKrw": "1300",
                  "partner": "GMEREMIT",
                  "userRef": "user-np-2",
                  "currency": "NPR"
                }
                """.formatted(fonepayQr);

        mockMvc.perform(post("/v1/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.schemeTxnRef", is("NP-SCHEME-999")))
                .andExpect(jsonPath("$.payCurrency", is("NPR")))
                .andExpect(jsonPath("$.payAmount", is("1300")));

        // The pay currency (NPR) must be threaded to the failover router — NOT treated as KRW.
        // And the limit subject must carry the wallet partner's code so its caps can be resolved.
        ArgumentCaptor<WalletPartnerRef> subject = ArgumentCaptor.forClass(WalletPartnerRef.class);
        verify(failoverPaymentRouter).pay(eq(fonepayQr), eq(new BigDecimal("1300")),
                eq("user-np-2"), anyString(), eq("NPR"), subject.capture());
        assertEquals("GMEREMIT", subject.getValue().code());
        // Domestic ZeroPay path untouched for a cross-border scan.
        verifyNoInteractions(gmeremitPaymentService);
    }

    // ---- Test: ZeroPay QR still routes to GmeremitPaymentService (unchanged) ----

    @Test
    @DisplayName("POST /v1/pay — ZeroPay QR still routes to GMEREMIT (failover router untouched)")
    void walletPay_zeropayQr_routesToGmeremit() throws Exception {
        String zeropayQr = "00020101021126260011com.zeropay010888888885802KR5910COFFEE HUT6304ABCD";

        WalletResult approved = WalletResult.approved(
                "GMEREMIT-42", "ZP-TXN-42", "Coffee Hut",
                new BigDecimal("5000"), new BigDecimal("500"), new BigDecimal("5500"),
                "2026-07-01T10:00:00+09:00");
        when(gmeremitPaymentService.pay(eq(zeropayQr), eq(new BigDecimal("5000")), eq("user-kr-1")))
                .thenReturn(approved);

        String body = """
                {
                  "qrPayload": "%s",
                  "amountKrw": "5000",
                  "partner": "GMEREMIT",
                  "userRef": "user-kr-1"
                }
                """.formatted(zeropayQr);

        mockMvc.perform(post("/v1/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.schemeTxnRef", is("ZP-TXN-42")));

        verify(gmeremitPaymentService).pay(eq(zeropayQr), eq(new BigDecimal("5000")), eq("user-kr-1"));
        verifyNoInteractions(failoverPaymentRouter);
    }

    // ---- Test 6: Refund happy path ----

    @Test
    @DisplayName("POST /v1/pay/{schemeTxnRef}/refund — REFUNDED: 200 with status=REFUNDED")
    void walletPay_refund_happyPath() throws Exception {
        doNothing().when(schemeClient).cancelPayment(cancelOf("AUTH-CPM-001"));

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

    /**
     * T2-7: the refund now rides {@link SchemeClient.CancelRequest} so it can be routed by scheme.
     * Matcher for "a cancel of this authId, any reason, any scheme".
     */
    private static SchemeClient.CancelRequest cancelOf(String authId) {
        return argThat(req -> req != null && authId.equals(req.schemeTxnRef()));
    }

    // ---- T2-6: the wallet refund path recorded the wrong status and booked nothing ----

    /** Registers an original wallet payment of 50 000 KRW so the refund has a basis to validate against. */
    private void givenOriginalWalletPayment(String txnRef, BigDecimal alreadyRefunded) {
        when(transactionClient.findRefundBasis(txnRef)).thenReturn(Optional.of(
                new TransactionClient.RefundBasis(txnRef, "APPROVED", new BigDecimal("50000"), "KRW",
                        new BigDecimal("37.5000"), alreadyRefunded)));
    }

    @Test
    @DisplayName("T2-6: a wallet refund lands as REFUNDED (not REVERSED) with the refunded KRW recorded")
    void walletRefund_landsAsRefundedWithItsAmount() throws Exception {
        givenOriginalWalletPayment("TXN-W1", null);
        doNothing().when(schemeClient).cancelPayment(cancelOf("AUTH-W1"));

        mockMvc.perform(post("/v1/pay/TXN-W1/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authId\":\"AUTH-W1\",\"reason\":\"CUSTOMER_REQUEST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REFUNDED")));

        ArgumentCaptor<TransactionClient.StatusPatch> patch =
                ArgumentCaptor.forClass(TransactionClient.StatusPatch.class);
        verify(transactionClient).commitStatus(eq("TXN-W1"), patch.capture());

        // The defect: this path patched REVERSED. refundedAt is stamped only on entry to REFUNDED, so every
        // wallet refund was invisible to GET /v1/transactions/refunded — and therefore to the settlement
        // claw-back. It is also the REFUNDED transition that emits payment.reversed, so revenue reversal and
        // the partner webhook both hung off getting this one enum right.
        assertEquals(PaymentStatus.REFUNDED, patch.getValue().newStatus(),
                "a refund must be recorded as REFUNDED; REVERSED is not found by findRefundedOn");
        assertEquals(0, new BigDecimal("50000").compareTo(patch.getValue().refundAmountKrw()),
                "the full collection amount is the refunded magnitude the claw-back nets");
    }

    @Test
    @DisplayName("T2-6: a wallet refund books a REAL reversal journal, never a zero rounding residual")
    void walletRefund_booksARealReversalNotAZeroResidual() throws Exception {
        givenOriginalWalletPayment("TXN-W2", null);
        doNothing().when(schemeClient).cancelPayment(cancelOf("AUTH-W2"));

        mockMvc.perform(post("/v1/pay/TXN-W2/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authId\":\"AUTH-W2\",\"reason\":\"CUSTOMER_REQUEST\"}"))
                .andExpect(status().isOk());

        // Was: postRoundingResidual(ref + "-REFUND", ZERO, "KRW") — an amount of nothing, in the rounding
        // account, under a reference nothing else uses. Nothing was booked at all.
        verify(revenueLedgerClient, never()).postRoundingResidual(anyString(), any(), anyString());
        verify(revenueLedgerClient).postReversalJournal(
                eq("TXN-W2"), eq(new BigDecimal("50000")), eq("KRW"));
    }

    @Test
    @DisplayName("T2-6: a PARTIAL wallet refund records only that amount")
    void walletRefund_partialRecordsOnlyThatAmount() throws Exception {
        givenOriginalWalletPayment("TXN-W3", null);
        doNothing().when(schemeClient).cancelPayment(cancelOf("AUTH-W3"));

        mockMvc.perform(post("/v1/pay/TXN-W3/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authId\":\"AUTH-W3\",\"amount\":\"20000\",\"currency\":\"KRW\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REFUNDED")))
                .andExpect(jsonPath("$.refundedAmount", is(20000)));

        ArgumentCaptor<TransactionClient.StatusPatch> patch =
                ArgumentCaptor.forClass(TransactionClient.StatusPatch.class);
        verify(transactionClient).commitStatus(eq("TXN-W3"), patch.capture());
        assertEquals(0, new BigDecimal("20000").compareTo(patch.getValue().refundAmountKrw()));
        verify(revenueLedgerClient).postReversalJournal(
                eq("TXN-W3"), eq(new BigDecimal("20000")), eq("KRW"));
    }

    @Test
    @DisplayName("T2-6: a wallet over-refund is rejected with a structured error and nothing is recorded")
    void walletRefund_overRefundIsRejected() throws Exception {
        // 40 000 already refunded of 50 000; asking for another 20 000 would refund 60 000.
        givenOriginalWalletPayment("TXN-W4", new BigDecimal("40000"));

        mockMvc.perform(post("/v1/pay/TXN-W4/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authId\":\"AUTH-W4\",\"amount\":\"20000\",\"currency\":\"KRW\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status", is("FAILED")))
                .andExpect(jsonPath("$.errorCode", is("REFUND_AMOUNT_EXCEEDS_ORIGINAL")));

        // Rejected before the scheme is even called, so no over-refund can reach the customer.
        verifyNoInteractions(schemeClient);
        verify(transactionClient, never()).commitStatus(any(), any());
        verifyNoInteractions(revenueLedgerClient);
    }

    @Test
    @DisplayName("T2-6: a wallet refund in a foreign currency is refused rather than converted")
    void walletRefund_foreignCurrencyIsRefused() throws Exception {
        givenOriginalWalletPayment("TXN-W5", null);

        mockMvc.perform(post("/v1/pay/TXN-W5/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authId\":\"AUTH-W5\",\"amount\":\"10\",\"currency\":\"USD\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode", is("REFUND_AMOUNT_INVALID")));

        verifyNoInteractions(schemeClient);
    }

    @Test
    @DisplayName("T2-6: a PARTIAL wallet refund is refused when the original payment cannot be read")
    void walletRefund_partialWithUnreadableOriginalIsRefused() throws Exception {
        when(transactionClient.findRefundBasis("TXN-W6")).thenReturn(Optional.empty());

        mockMvc.perform(post("/v1/pay/TXN-W6/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authId\":\"AUTH-W6\",\"amount\":\"10000\",\"currency\":\"KRW\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode", is("REFUND_BASIS_UNAVAILABLE")));

        verifyNoInteractions(schemeClient);
    }

    @Test
    @DisplayName("T2-6: a FULL wallet refund with an unreadable original still works, and books nothing")
    void walletRefund_fullWithUnreadableOriginalStillRefundsButBooksNothing() throws Exception {
        when(transactionClient.findRefundBasis("TXN-W7")).thenReturn(Optional.empty());
        doNothing().when(schemeClient).cancelPayment(cancelOf("AUTH-W7"));

        mockMvc.perform(post("/v1/pay/TXN-W7/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authId\":\"AUTH-W7\",\"reason\":\"CUSTOMER_REQUEST\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REFUNDED")));

        // The status still moves (the legacy path's guarantee), but with no amount known nothing is
        // journalled — an honest blank rather than the zero residual that used to read as "booked".
        verify(transactionClient).commitStatus(eq("TXN-W7"), any());
        verifyNoInteractions(revenueLedgerClient);
    }

    // ---- T2-7: refund routes by scheme; an unsupported corridor is a structured error ----

    @Test
    @DisplayName("T2-7: SENDMN refund → 422 SCHEME_OPERATION_UNSUPPORTED, nothing downstream touched")
    void walletPay_refund_sendmnUnsupported_structuredError() throws Exception {
        doThrow(new SchemeOperationNotSupportedException("SENDMN", "cancelPayment",
                "SENDMN Confirm is single-shot (authorize+commit); the scheme documents no cancel"))
                .when(schemeClient).cancelPayment(any(SchemeClient.CancelRequest.class));

        String body = """
                {
                  "authId": "SMN-PAYMENT-1",
                  "reason": "CUSTOMER_REQUEST",
                  "schemeId": "SENDMN"
                }
                """;

        mockMvc.perform(post("/v1/pay/SMN-TXN-1/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status", is("FAILED")))
                // The whole point of T2-7: an explicit "no scheme refund path", NOT a ZeroPay decline.
                .andExpect(jsonPath("$.errorCode", is("SCHEME_OPERATION_UNSUPPORTED")));

        // The scheme code must have been threaded through so the router could dispatch it.
        ArgumentCaptor<SchemeClient.CancelRequest> captor =
                ArgumentCaptor.forClass(SchemeClient.CancelRequest.class);
        verify(schemeClient).cancelPayment(captor.capture());
        assertEquals("SENDMN", captor.getValue().schemeId());
        assertEquals("SMN-PAYMENT-1", captor.getValue().schemeTxnRef());

        // No half-applied refund: the txn status and the revenue ledger are left alone.
        // T2-6 narrowed this from verifyNoInteractions(transactionClient): the refund now READS the original
        // payment (findRefundBasis) before the scheme call so an over-refund is refused without touching the
        // scheme. A read is not a mutation — what must not happen is a status WRITE, which is what this now
        // asserts, and it is still the exact guarantee T2-7 is about.
        verify(transactionClient, never()).commitStatus(any(), any());
        verifyNoInteractions(revenueLedgerClient);
    }

    @Test
    @DisplayName("T2-7: a refund without schemeId keeps the legacy (ZeroPay-default) routing")
    void walletPay_refund_noSchemeId_legacyRouting() throws Exception {
        doNothing().when(schemeClient).cancelPayment(any(SchemeClient.CancelRequest.class));

        String body = """
                {
                  "authId": "AUTH-CPM-001",
                  "reason": "CUSTOMER_REQUEST"
                }
                """;

        mockMvc.perform(post("/v1/pay/TXN-LEGACY/refund")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REFUNDED")));

        ArgumentCaptor<SchemeClient.CancelRequest> captor =
                ArgumentCaptor.forClass(SchemeClient.CancelRequest.class);
        verify(schemeClient).cancelPayment(captor.capture());
        assertNull(captor.getValue().schemeId(),
                "absent schemeId must stay null so the router keeps its ZeroPay default");
    }

    // ---- Test 7: Refund — scheme declines (already refunded) → 422 ----

    @Test
    @DisplayName("POST /v1/pay/{schemeTxnRef}/refund — scheme decline: 422 with status=FAILED")
    void walletPay_refund_schemeDeclines_422() throws Exception {
        doThrow(new SchemeDeclinedException("ALREADY_REFUNDED", "Transaction already refunded"))
                .when(schemeClient).cancelPayment(any(SchemeClient.CancelRequest.class));

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

    // ---- Operations operational gate ----

    @Test
    @DisplayName("POST /v1/pay — systemPaused: 503 SYSTEM_PAUSED, payment service NOT touched")
    void walletPay_systemPaused_rejected() throws Exception {
        doThrow(new OperationalGateException(OperationalGateException.SYSTEM_PAUSED,
                "platform is paused — new payments are not being accepted"))
                .when(operationalGate).checkNewAuthorization(anyString(), any(), any(), any(), any());

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
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code", is("SYSTEM_PAUSED")))
                .andExpect(jsonPath("$.retryable", is(true)));

        // A paused platform must not run the payment.
        verifyNoInteractions(gmeremitPaymentService);
    }

    @Test
    @DisplayName("POST /v1/pay — suspended partner: 503 PARTNER_SUSPENDED")
    void walletPay_partnerSuspended_rejected() throws Exception {
        doThrow(new OperationalGateException(OperationalGateException.PARTNER_SUSPENDED,
                "partner 'GMEREMIT' is currently suspended"))
                .when(operationalGate).checkNewAuthorization(anyString(), any(), any(), any(), any());

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
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code", is("PARTNER_SUSPENDED")));

        verifyNoInteractions(gmeremitPaymentService);
    }

    @Test
    @DisplayName("POST /v1/pay/{ref}/refund — in-flight refund NOT gated even when platform paused")
    void walletPay_refundNotGated_whenPaused() throws Exception {
        // Even if the gate WOULD pause a new payment, a refund of an in-flight txn must proceed:
        // the refund path never calls the gate, so a stubbed pause has no effect here.
        doThrow(new OperationalGateException(OperationalGateException.SYSTEM_PAUSED, "paused"))
                .when(operationalGate).checkNewAuthorization(anyString(), any(), any(), any(), any());
        doNothing().when(schemeClient).cancelPayment(cancelOf("AUTH-CPM-001"));

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
                .andExpect(jsonPath("$.status", is("REFUNDED")));

        // The gate must never be consulted on the in-flight refund path.
        verifyNoInteractions(operationalGate);
    }

    // ---- T3-6: the scheme's own operating window, on the WALLET entry point ----

    @Test
    @DisplayName("T3-6: closed scheme on /v1/pay → 409 SCHEME_CLOSED, no scheme call, no payment")
    void walletPay_closedScheme_structuredError_noSideEffect() throws Exception {
        // The gate the controller calls is the SAME OperationalGate the orchestrated authorize path
        // calls, so the wallet path cannot drift from it (the T4-2 split this gap refused to repeat).
        doThrow(new SchemeClosedException(com.gme.pay.contracts.SchemeAvailability.evaluate(
                "ZEROPAY",
                java.util.List.of(new com.gme.pay.contracts.SchemeOperatingHoursView(
                        "ZEROPAY", 1, java.time.LocalTime.of(18, 0), java.time.LocalTime.of(22, 0),
                        java.time.LocalTime.of(16, 30), "Asia/Seoul")),
                java.time.Instant.parse("2026-07-28T03:00:00Z"))))
                .when(operationalGate).checkNewAuthorization(anyString(), any(), any(), any(), any());

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
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("SCHEME_CLOSED")))
                .andExpect(jsonPath("$.retryable", is(false)));

        // Nothing moved: no corridor service ran, so no float was deducted and no scheme was called.
        verifyNoInteractions(gmeremitPaymentService);
        verifyNoInteractions(sendmnPaymentService);
        verifyNoInteractions(failoverPaymentRouter);
        verifyNoInteractions(schemeClient);
    }

    @Test
    @DisplayName("T3-6: the wallet path passes the DISPATCHED scheme (ZEROPAY/SENDMN), never a guess")
    void walletPay_gatePassesTheDispatchedSchemeReference() throws Exception {
        stubApproved();
        mockMvc.perform(post("/v1/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(IDEM_BODY))
                .andExpect(status().isCreated());

        // GMEREMIT is the ZeroPay domestic corridor — the gate must receive a scheme reference, or the
        // seeded V024 window could never be evaluated on this entry point at all (the T3-6 defect).
        verify(operationalGate).checkNewAuthorization(eq("GMEREMIT"), eq("ZEROPAY"), any(), any(), any());
    }

    @Test
    @DisplayName("T3-6: refund is NOT gated by a closed window (the deliberate carve-out)")
    void walletRefund_notGated_whenSchemeClosed() throws Exception {
        // A closed rail must not trap a customer's money: the refund path never calls the gate, so even
        // a gate stubbed to reject every new payment has no effect here.
        doThrow(new SchemeClosedException(com.gme.pay.contracts.SchemeAvailability.evaluate(
                "ZEROPAY", java.util.List.of(), java.time.Instant.now())))
                .when(operationalGate).checkNewAuthorization(anyString(), any(), any(), any(), any());
        doNothing().when(schemeClient).cancelPayment(cancelOf("AUTH-CPM-001"));

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
                .andExpect(jsonPath("$.status", is("REFUNDED")));

        verifyNoInteractions(operationalGate);
    }

    // ---- T4-2: a limit refusal is a STRUCTURED error, mirroring POST /v1/payments/authorize ----

    @Test
    @DisplayName("T4-2: per-txn limit breach on /v1/pay → 422 TRANSACTION_LIMIT_EXCEEDED (ApiError shape)")
    void walletPay_perTxnLimitBreach_structuredError() throws Exception {
        // The corridor service raises the same exception the authorize gate raises; the wallet endpoint
        // must surface the canonical ApiError, not a WalletPaymentResponse decline body.
        when(gmeremitPaymentService.pay(anyString(), any(BigDecimal.class), any()))
                .thenThrow(new TransactionLimitExceededException(
                        "GMEREMIT", new BigDecimal("6000"), new BigDecimal("5000"), "MAX"));

        String body = """
                {
                  "qrPayload": "ZPQR0001",
                  "amountKrw": "8100000",
                  "partner": "GMEREMIT",
                  "userRef": "user-007"
                }
                """;

        mockMvc.perform(post("/v1/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("TRANSACTION_LIMIT_EXCEEDED")))
                .andExpect(jsonPath("$.retryable", is(false)));

        // No scheme call may be attempted from the controller on a limit refusal.
        verifyNoInteractions(schemeClient);
    }

    // ---- Request-level idempotency (Idempotency-Key header) ----

    private static final String IDEM_BODY = """
            {
              "qrPayload": "ZPQR0001",
              "amountKrw": "50000",
              "partner": "GMEREMIT",
              "userRef": "user-007"
            }
            """;

    private void stubApproved() {
        WalletResult approved = WalletResult.approved(
                "GMEREMIT-9001", "TXN-AABB1122", "Coffee Shop",
                new BigDecimal("50000"), new BigDecimal("500"), new BigDecimal("50500"),
                "2026-06-13T11:23:45+09:00");
        when(gmeremitPaymentService.pay(eq("ZPQR0001"), eq(new BigDecimal("50000")), eq("user-007")))
                .thenReturn(approved);
    }

    /**
     * Same key + same body twice: the payment executes exactly ONCE and the second call REPLAYS the
     * identical recorded response without re-hitting the payment service.
     */
    @Test
    @DisplayName("POST /v1/pay — same Idempotency-Key + same body: executed once, second call replays")
    void idempotency_sameKeySameBody_executesOnce_replays() throws Exception {
        stubApproved();

        // The claim row the controller inserts; captured so the replay can find it with a recorded body.
        AtomicReference<IdempotencyRecordEntity> stored = new AtomicReference<>();
        when(idempotencyRepository.saveAndFlush(any(IdempotencyRecordEntity.class)))
                .thenAnswer(inv -> {
                    IdempotencyRecordEntity e = inv.getArgument(0);
                    if (stored.get() == null) {
                        stored.set(e);          // first save = the claim
                        return e;
                    }
                    // subsequent saveAndFlush on the SAME instance = recordOutcome; keep it.
                    stored.set(e);
                    return e;
                });
        // First request: no existing row (claim succeeds).
        when(idempotencyRepository.findByPartnerIdAndIdempotencyKey(anyLong(), eq("KEY-1")))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/v1/pay")
                        .header("Idempotency-Key", "KEY-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(IDEM_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.schemeTxnRef", is("TXN-AABB1122")));

        // Second (retry) request: claim collides, and the recorded row is now found for replay.
        when(idempotencyRepository.saveAndFlush(any(IdempotencyRecordEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_idempotency_partner_key"));
        when(idempotencyRepository.findByPartnerIdAndIdempotencyKey(anyLong(), eq("KEY-1")))
                .thenReturn(Optional.of(stored.get()));

        mockMvc.perform(post("/v1/pay")
                        .header("Idempotency-Key", "KEY-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(IDEM_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.schemeTxnRef", is("TXN-AABB1122")));

        // The non-negotiable: exactly ONE payment executed across both calls.
        verify(gmeremitPaymentService, times(1))
                .pay(eq("ZPQR0001"), eq(new BigDecimal("50000")), eq("user-007"));
    }

    /** Same key but a DIFFERENT payload → 422 idempotency_key_reuse; payment NOT executed. */
    @Test
    @DisplayName("POST /v1/pay — same key + different body: 422 idempotency_key_reuse, no execution")
    void idempotency_sameKeyDifferentBody_422() throws Exception {
        // Existing row was claimed with a DIFFERENT payload (different amount → different hash).
        IdempotencyRecordEntity priorRow = new IdempotencyRecordEntity(
                1L, "KEY-2", "hash-of-a-different-payload", java.time.Instant.now());
        when(idempotencyRepository.saveAndFlush(any(IdempotencyRecordEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_idempotency_partner_key"));
        when(idempotencyRepository.findByPartnerIdAndIdempotencyKey(anyLong(), eq("KEY-2")))
                .thenReturn(Optional.of(priorRow));

        mockMvc.perform(post("/v1/pay")
                        .header("Idempotency-Key", "KEY-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(IDEM_BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error", is("idempotency_key_reuse")));

        verifyNoInteractions(gmeremitPaymentService);
    }

    /** Concurrent duplicate claim, same hash, no stored response yet → 409 idempotency_in_progress. */
    @Test
    @DisplayName("POST /v1/pay — concurrent claim, no recorded response yet: 409 idempotency_in_progress")
    void idempotency_inProgress_409() throws Exception {
        // Compute the hash the controller will compute for IDEM_BODY by letting the first (real)
        // request run through — simplest: the in-flight row carries the SAME hash but no response.
        // We stub the collision then return a row whose request_hash matches this exact body.
        AtomicReference<String> hash = new AtomicReference<>();
        when(idempotencyRepository.saveAndFlush(any(IdempotencyRecordEntity.class)))
                .thenAnswer(inv -> {
                    hash.set(((IdempotencyRecordEntity) inv.getArgument(0)).getRequestHash());
                    throw new DataIntegrityViolationException("uq_idempotency_partner_key");
                });
        when(idempotencyRepository.findByPartnerIdAndIdempotencyKey(anyLong(), eq("KEY-3")))
                .thenAnswer(inv -> {
                    // In-flight first request: same hash, response_body still null.
                    IdempotencyRecordEntity inFlight = new IdempotencyRecordEntity(
                            1L, "KEY-3", hash.get(), java.time.Instant.now());
                    return Optional.of(inFlight);
                });

        mockMvc.perform(post("/v1/pay")
                        .header("Idempotency-Key", "KEY-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(IDEM_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("idempotency_in_progress")));

        verifyNoInteractions(gmeremitPaymentService);
    }

    /** No header → behaviour identical to today; idempotency store is never touched (back-compat). */
    @Test
    @DisplayName("POST /v1/pay — no Idempotency-Key header: unchanged behaviour, store untouched")
    void idempotency_noHeader_backCompat() throws Exception {
        stubApproved();

        mockMvc.perform(post("/v1/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(IDEM_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.schemeTxnRef", is("TXN-AABB1122")));

        verify(gmeremitPaymentService, times(1))
                .pay(eq("ZPQR0001"), eq(new BigDecimal("50000")), eq("user-007"));
        // Without the header the idempotency path is entirely bypassed.
        verifyNoInteractions(idempotencyRepository);
    }
}
