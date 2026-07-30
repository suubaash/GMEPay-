package com.gme.pay.payment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.contracts.OperationalStatusView;
import com.gme.pay.contracts.SchemeOperatingHoursView;
import com.gme.pay.events.RecordingEventPublisher;
import com.gme.pay.payment.domain.OperationalGate;
import com.gme.pay.payment.domain.PaymentOrchestrator;
import com.gme.pay.payment.domain.PaymentOrchestrator.PaymentResult;
import com.gme.pay.payment.domain.PaymentStatus;
import com.gme.pay.payment.domain.SchemeOperatingHoursGate;
import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.domain.client.SchemeOperatingHoursClient;
import com.gme.pay.payment.persistence.PaymentAuthorizationEntity;
import com.gme.pay.payment.persistence.PaymentAuthorizationRepository;
import com.gme.pay.payment.service.PaymentAuthorizationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * T3-6 on the ORCHESTRATED entry point: {@code POST /v1/payments/authorize} refuses a payment aimed at a
 * scheme outside its seeded operating window, while {@code /confirm} of an already-authorized payment is
 * untouched.
 *
 * <p>The gate is wired for REAL here (a real {@link OperationalGate} composing a real
 * {@link SchemeOperatingHoursGate} over a stub schedule + fixed clock) — a mocked gate would prove only
 * that the controller calls something. The companion wallet-path proof lives in
 * {@code WalletPayControllerTest}; between them, both new-payment entry points are pinned.
 */
class PaymentControllerSchemeClosedTest {

    /** 2026-07-28 is a TUESDAY → V024 weekday 1. 03:00Z = 12:00 KST. */
    private static final Instant TUE_NOON_KST = Instant.parse("2026-07-28T03:00:00Z");

    private static final String AUTHORIZE_BODY = """
            {
              "quote_id": "Q-1",
              "merchant_qr": "ZPQR0001",
              "direction": "INBOUND",
              "scheme_id": "ZEROPAY",
              "customer_ref": "cust-1",
              "partner_txn_ref": "PTR-T36-1",
              "collection_amount": "50000",
              "collection_currency": "KRW"
            }
            """;

    private final PaymentOrchestrator orchestrator = mock(PaymentOrchestrator.class);
    private final PartnerConfigClient partnerConfigClient = mock(PartnerConfigClient.class);
    private final PaymentAuthorizationRepository authorizationRepository =
            mock(PaymentAuthorizationRepository.class);
    private final PaymentAuthorizationService authorizationService =
            mock(PaymentAuthorizationService.class);
    private final RecordingEventPublisher eventPublisher = new RecordingEventPublisher();

    /** A schedule source returning one fixed weekly row set for every scheme. */
    private static SchemeOperatingHoursClient schedule(List<SchemeOperatingHoursView> rows) {
        return schemeId -> rows;
    }

    private static SchemeOperatingHoursView row(String open, String close, String cutoff) {
        return new SchemeOperatingHoursView("ZEROPAY", 1, LocalTime.parse(open), LocalTime.parse(close),
                cutoff == null ? null : LocalTime.parse(cutoff), "Asia/Seoul");
    }

    private MockMvc mvcWith(SchemeOperatingHoursClient hours) {
        SchemeOperatingHoursGate hoursGate = new SchemeOperatingHoursGate(
                hours, null, true, Clock.fixed(TUE_NOON_KST, ZoneOffset.UTC));
        PaymentController controller = new PaymentController(
                orchestrator, partnerConfigClient, authorizationRepository, authorizationService,
                eventPublisher,
                new OperationalGate(OperationalStatusView::allClear, hoursGate));
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return standaloneSetup(controller)
                .setControllerAdvice(new PaymentExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    @DisplayName("authorize outside the window → 409 SCHEME_CLOSED; orchestrator never runs")
    void authorize_closedScheme_rejected_beforeAnySideEffect() throws Exception {
        when(authorizationRepository.findByPartnerIdAndPartnerTxnRef(anyLong(), any()))
                .thenReturn(Optional.empty());

        // ZEROPAY open 18:00-22:00 Seoul; the clock says 12:00 KST Tuesday.
        mvcWith(schedule(List.of(row("18:00", "22:00", "16:30"))))
                .perform(post("/v1/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(AUTHORIZE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCHEME_CLOSED"))
                .andExpect(jsonPath("$.retryable").value(false));

        // The gate runs before the quote agreement-check, the merchant resolve and the float RESERVE,
        // so nothing was held, nothing was created and no scheme was contacted.
        verify(orchestrator, never()).authorizeMpm(any(), any());
        verifyNoInteractions(authorizationService);
        org.assertj.core.api.Assertions.assertThat(eventPublisher.published()).isEmpty();
    }

    @Test
    @DisplayName("authorize inside the window proceeds normally")
    void authorize_openScheme_proceeds() throws Exception {
        when(authorizationRepository.findByPartnerIdAndPartnerTxnRef(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(orchestrator.authorizeMpm(any(), any()))
                .thenThrow(new IllegalStateException("reached the orchestrator"));

        // 09:00-18:00 Seoul contains 12:00 KST → the gate must NOT reject; the orchestrator is reached
        // (its stubbed throw is the proof, without needing a full AuthorizeResult fixture).
        try {
            mvcWith(schedule(List.of(row("09:00", "18:00", "16:30"))))
                    .perform(post("/v1/payments/authorize")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(AUTHORIZE_BODY));
        } catch (Exception expected) {
            // standaloneSetup rethrows the unhandled IllegalStateException — that IS "we got past the gate".
        }
        verify(orchestrator).authorizeMpm(any(), any());
    }

    @Test
    @DisplayName("an UNVERIFIED window does not block the orchestrated path either")
    void authorize_unverifiedWindow_proceeds() throws Exception {
        when(authorizationRepository.findByPartnerIdAndPartnerTxnRef(anyLong(), any()))
                .thenReturn(Optional.empty());
        when(orchestrator.authorizeMpm(any(), any()))
                .thenThrow(new IllegalStateException("reached the orchestrator"));

        try {
            mvcWith(schedule(List.of()))   // no seeded rows at all ⇒ UNVERIFIED
                    .perform(post("/v1/payments/authorize")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(AUTHORIZE_BODY));
        } catch (Exception expected) {
            // as above
        }
        verify(orchestrator).authorizeMpm(any(), any());
    }

    @Test
    @DisplayName("CONFIRM of an already-authorized payment still succeeds while the scheme is CLOSED")
    void confirm_isNotGated_whenSchemeClosed() throws Exception {
        PaymentAuthorizationEntity auth = new PaymentAuthorizationEntity();
        auth.setAuthId("AUTH-T36");
        auth.setPartnerId(1L);
        auth.setPartnerType("OVERSEAS");
        auth.setPartnerTxnRef("PTR-T36-1");
        auth.setSchemeId("ZEROPAY");
        auth.setMerchantId("M-1");
        auth.setMerchantName("Cafe");
        auth.setTargetPayout(new BigDecimal("50000"));
        auth.setPayoutCurrency("KRW");
        auth.setCollectionAmount(new BigDecimal("50000"));
        auth.setCollectionCurrency("KRW");
        auth.setTxnRef("txn_t36");
        auth.setPaymentId("pay_t36");
        auth.setStatus(PaymentAuthorizationEntity.STATUS_AUTHORIZED);
        auth.setCreatedAt(TUE_NOON_KST);
        auth.setExpiresAt(Instant.parse("2099-01-01T00:00:00Z"));

        when(authorizationRepository.findById("AUTH-T36")).thenReturn(Optional.of(auth));
        when(authorizationService.compareAndSetStatus("AUTH-T36",
                PaymentAuthorizationEntity.STATUS_AUTHORIZED,
                PaymentAuthorizationEntity.STATUS_CONFIRMING)).thenReturn(true);
        when(orchestrator.confirmMpm(any())).thenReturn(new PaymentResult(
                "pay_t36", PaymentStatus.APPROVED, "ZP_TXN_1", "Cafe", "M-1",
                new BigDecimal("50000"), "KRW", null,
                new BigDecimal("50000"), "KRW", new BigDecimal("500"), "KRW", null,
                "PTR-T36-1", TUE_NOON_KST, TUE_NOON_KST));

        // The window is CLOSED for a NEW payment — and the in-flight confirm must still complete.
        mvcWith(schedule(List.of(row("18:00", "22:00", "16:30"))))
                .perform(post("/v1/payments/AUTH-T36/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"wallet_charge_ref\":\"WCR-T36\"}"))
                .andExpect(status().isCreated());

        verify(orchestrator).confirmMpm(any());
    }

    @Test
    @DisplayName("an idempotent authorize REPLAY is not re-gated by a closed window")
    void authorizeReplay_isNotReGated() throws Exception {
        PaymentAuthorizationEntity existing = new PaymentAuthorizationEntity();
        existing.setAuthId("AUTH-T36-REPLAY");
        existing.setPartnerId(1L);
        existing.setPartnerType("OVERSEAS");
        existing.setPartnerTxnRef("PTR-T36-1");
        existing.setSchemeId("ZEROPAY");
        existing.setTargetPayout(new BigDecimal("50000"));
        existing.setPayoutCurrency("KRW");
        existing.setCollectionAmount(new BigDecimal("50000"));
        existing.setCollectionCurrency("KRW");
        existing.setStatus(PaymentAuthorizationEntity.STATUS_AUTHORIZED);
        existing.setCreatedAt(TUE_NOON_KST);
        existing.setExpiresAt(Instant.parse("2099-01-01T00:00:00Z"));
        when(authorizationRepository.findByPartnerIdAndPartnerTxnRef(anyLong(), any()))
                .thenReturn(Optional.of(existing));

        // Same in-flight rule as the operator pause: a replay of an ALREADY-authorized txn returns the
        // existing authorization even though a NEW one would now be refused.
        mvcWith(schedule(List.of(row("18:00", "22:00", "16:30"))))
                .perform(post("/v1/payments/authorize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(AUTHORIZE_BODY))
                .andExpect(status().isCreated());

        verify(orchestrator, never()).authorizeMpm(any(), any());
    }
}
