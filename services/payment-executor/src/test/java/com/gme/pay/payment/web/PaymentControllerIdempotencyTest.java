package com.gme.pay.payment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.events.RecordingEventPublisher;
import com.gme.pay.payment.domain.PaymentOrchestrator;
import com.gme.pay.payment.domain.PaymentOrchestrator.PaymentResult;
import com.gme.pay.payment.domain.PaymentStatus;
import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.persistence.PaymentAuthorizationEntity;
import com.gme.pay.payment.persistence.PaymentAuthorizationRepository;
import com.gme.pay.payment.service.PaymentAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Standalone MockMvc tests for the two-phase confirm endpoint's ATOMIC-CLAIM concurrency guard
 * (POST /v1/payments/{authId}/confirm). The single-shot POST /v1/payments + its Idempotency-Key
 * replay were retired in Step 4, so the live MPM flow is authorize→confirm only.
 */
class PaymentControllerIdempotencyTest {

    private PaymentOrchestrator orchestrator;
    private PartnerConfigClient partnerConfigClient;
    private PaymentAuthorizationRepository authorizationRepository;
    private PaymentAuthorizationService authorizationService;
    private RecordingEventPublisher eventPublisher;
    private MockMvc mvc;

    private static PaymentResult sampleResult(String paymentId) {
        return new PaymentResult(
                paymentId, PaymentStatus.APPROVED, "ZP_TXN_1", "Cafe", "M-1",
                new BigDecimal("1000"), "KRW", null,
                new BigDecimal("1000"), "KRW", new BigDecimal("500"), "KRW", null,
                "PTR1", Instant.parse("2026-06-15T00:00:00Z"), Instant.parse("2026-06-15T00:00:01Z"));
    }

    private static PaymentAuthorizationEntity authorizedEntity() {
        PaymentAuthorizationEntity e = new PaymentAuthorizationEntity();
        e.setAuthId("AUTH-1");
        e.setPartnerId(1L);
        e.setPartnerType("OVERSEAS");
        e.setPartnerTxnRef("PTR1");
        e.setSchemeId("zeropay");
        e.setMerchantId("M-1");
        e.setMerchantName("Cafe");
        e.setTargetPayout(new BigDecimal("50000"));
        e.setPayoutCurrency("KRW");
        e.setCollectionAmount(new BigDecimal("131385.49"));
        e.setCollectionCurrency("MNT");
        e.setCollectionMarginUsd(new BigDecimal("1.2345"));
        e.setPayoutMarginUsd(new BigDecimal("0.6789"));
        e.setServiceCharge(new BigDecimal("0.50"));
        e.setTxnRef("txn_1");
        e.setPaymentId("pay_1");
        e.setStatus(PaymentAuthorizationEntity.STATUS_AUTHORIZED);
        e.setCreatedAt(Instant.parse("2026-06-15T00:00:00Z"));
        e.setExpiresAt(Instant.parse("2099-01-01T00:00:00Z")); // far future = not expired
        return e;
    }

    @BeforeEach
    void setUp() {
        orchestrator = mock(PaymentOrchestrator.class);
        partnerConfigClient = mock(PartnerConfigClient.class);
        authorizationRepository = mock(PaymentAuthorizationRepository.class);
        authorizationService = mock(PaymentAuthorizationService.class);
        eventPublisher = new RecordingEventPublisher();
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        PaymentController controller = new PaymentController(
                orchestrator, partnerConfigClient, authorizationRepository, authorizationService,
                eventPublisher);
        mvc = standaloneSetup(controller)
                .setControllerAdvice(new PaymentExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void confirm_lostClaim_rejectsAndNeverSubmitsToScheme() throws Exception {
        when(authorizationRepository.findById("AUTH-1")).thenReturn(Optional.of(authorizedEntity()));
        // This caller LOSES the atomic claim (another confirm already moved it past AUTHORIZED).
        when(authorizationService.compareAndSetStatus("AUTH-1",
                PaymentAuthorizationEntity.STATUS_AUTHORIZED,
                PaymentAuthorizationEntity.STATUS_CONFIRMING)).thenReturn(false);

        mvc.perform(post("/v1/payments/AUTH-1/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"wallet_charge_ref\":\"WCR1\"}"))
                .andExpect(status().isBadRequest());

        // The non-negotiable: a lost claim must NEVER reach the scheme.
        verify(orchestrator, never()).confirmMpm(any());
        // ...and emits no lifecycle event (no approved/failed for a rejected claim).
        org.assertj.core.api.Assertions.assertThat(eventPublisher.published()).isEmpty();
    }

    @Test
    void confirm_wonClaim_submitsOnceAndMarksConfirmed() throws Exception {
        when(authorizationRepository.findById("AUTH-1")).thenReturn(Optional.of(authorizedEntity()));
        when(authorizationService.compareAndSetStatus("AUTH-1",
                PaymentAuthorizationEntity.STATUS_AUTHORIZED,
                PaymentAuthorizationEntity.STATUS_CONFIRMING)).thenReturn(true);
        when(orchestrator.confirmMpm(any())).thenReturn(sampleResult("pay_1"));

        mvc.perform(post("/v1/payments/AUTH-1/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"wallet_charge_ref\":\"WCR1\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment_id").value("pay_1"));

        verify(orchestrator).confirmMpm(any());
        verify(authorizationService).markOutcome(eq("AUTH-1"),
                eq(PaymentAuthorizationEntity.STATUS_CONFIRMED), eq("WCR1"), any());
        // A successful confirm EXPOSES exactly one payment.approved event keyed by payment_id.
        org.assertj.core.api.Assertions.assertThat(eventPublisher.published()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(eventPublisher.published().get(0).eventType())
                .isEqualTo("payment.approved");
        org.assertj.core.api.Assertions.assertThat(eventPublisher.published().get(0).aggregateId())
                .isEqualTo("pay_1");
    }

    @Test
    void confirm_wonClaim_emitsCanonicalPaymentApprovedPayloadWithRevenueFields() throws Exception {
        when(authorizationRepository.findById("AUTH-1")).thenReturn(Optional.of(authorizedEntity()));
        when(authorizationService.compareAndSetStatus("AUTH-1",
                PaymentAuthorizationEntity.STATUS_AUTHORIZED,
                PaymentAuthorizationEntity.STATUS_CONFIRMING)).thenReturn(true);
        when(orchestrator.confirmMpm(any())).thenReturn(sampleResult("pay_1"));

        mvc.perform(post("/v1/payments/AUTH-1/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"wallet_charge_ref\":\"WCR1\"}"))
                .andExpect(status().isCreated());

        var event = (com.gme.pay.payment.domain.event.PaymentEvents.PaymentApproved)
                eventPublisher.published().get(0);
        var payload = event.payload();
        // Canonical lib-api-contracts payload (consumed by revenue-ledger + notification-webhook).
        org.assertj.core.api.Assertions.assertThat(payload.eventType()).isEqualTo("payment.approved");
        org.assertj.core.api.Assertions.assertThat(payload.aggregateId()).isEqualTo("pay_1");
        org.assertj.core.api.Assertions.assertThat(payload.txnRef()).isEqualTo("txn_1");
        org.assertj.core.api.Assertions.assertThat(payload.partnerId()).isEqualTo(1L);
        org.assertj.core.api.Assertions.assertThat(payload.collectionMarginUsd())
                .isEqualByComparingTo(new BigDecimal("1.2345"));
        org.assertj.core.api.Assertions.assertThat(payload.payoutMarginUsd())
                .isEqualByComparingTo(new BigDecimal("0.6789"));
        org.assertj.core.api.Assertions.assertThat(payload.serviceChargeAmount())
                .isEqualByComparingTo(new BigDecimal("0.50"));
        org.assertj.core.api.Assertions.assertThat(payload.serviceChargeCcy()).isEqualTo("MNT");
        org.assertj.core.api.Assertions.assertThat(payload.feeSharePct())
                .isEqualByComparingTo(new BigDecimal("0.70"));
        org.assertj.core.api.Assertions.assertThat(payload.revenueDate()).isNotNull();
    }
}
