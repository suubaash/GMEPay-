package com.gme.pay.payment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.payment.domain.FailoverPaymentRouter;
import com.gme.pay.payment.domain.GmeremitPaymentService;
import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import com.gme.pay.payment.domain.OperationalGate;
import com.gme.pay.payment.domain.OperationalGateException;
import com.gme.pay.payment.domain.SchemeDeclinedException;
import com.gme.pay.payment.domain.SendmnPaymentService;
import com.gme.pay.payment.domain.client.RevenueLedgerClient;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.TransactionClient;
import com.gme.pay.payment.persistence.IdempotencyRecordEntity;
import com.gme.pay.payment.persistence.IdempotencyRecordRepository;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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
        when(failoverPaymentRouter.pay(eq(fonepayQr), eq(new BigDecimal("1000")), eq("user-np-1"),
                anyString(), eq("KRW")))
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
                eq("user-np-2"), anyString(), eq("NPR")))
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
        verify(failoverPaymentRouter).pay(eq(fonepayQr), eq(new BigDecimal("1300")),
                eq("user-np-2"), anyString(), eq("NPR"));
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

    // ---- Operations operational gate ----

    @Test
    @DisplayName("POST /v1/pay — systemPaused: 503 SYSTEM_PAUSED, payment service NOT touched")
    void walletPay_systemPaused_rejected() throws Exception {
        doThrow(new OperationalGateException(OperationalGateException.SYSTEM_PAUSED,
                "platform is paused — new payments are not being accepted"))
                .when(operationalGate).checkNewAuthorization(anyString(), any(), any());

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
                .when(operationalGate).checkNewAuthorization(anyString(), any(), any());

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
                .when(operationalGate).checkNewAuthorization(anyString(), any(), any());
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
                .andExpect(jsonPath("$.status", is("REFUNDED")));

        // The gate must never be consulted on the in-flight refund path.
        verifyNoInteractions(operationalGate);
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
