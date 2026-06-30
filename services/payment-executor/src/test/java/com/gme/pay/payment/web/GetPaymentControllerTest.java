package com.gme.pay.payment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.payment.domain.PaymentOrchestrator;
import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.persistence.PaymentAuthorizationEntity;
import com.gme.pay.payment.persistence.PaymentAuthorizationRepository;
import com.gme.pay.payment.service.PaymentAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Standalone MockMvc tests for GET /v1/payments/{id} status retrieval (backlog 5.2-T16):
 * owner scoping (404 not 403 for a foreign payment), lowercase API status mapping, and the
 * OVERSEAS-only {@code prefund_deducted_usd} field.
 */
class GetPaymentControllerTest {

    private PaymentAuthorizationRepository authorizationRepository;
    private MockMvc mvc;

    private static PaymentAuthorizationEntity confirmed(String partnerType) {
        PaymentAuthorizationEntity e = new PaymentAuthorizationEntity();
        e.setAuthId("AUTH-1");
        e.setPartnerId(101L);
        e.setPartnerType(partnerType);
        e.setPartnerTxnRef("PTR1");
        e.setSchemeId("zeropay");
        e.setDirection("inbound");
        e.setMerchantId("M-1");
        e.setMerchantName("Cafe");
        e.setTargetPayout(new BigDecimal("50000"));
        e.setPayoutCurrency("KRW");
        e.setCollectionAmount(new BigDecimal("35.77"));
        e.setCollectionCurrency("USD");
        e.setServiceCharge(new BigDecimal("0.50"));
        e.setReservedUsd(new BigDecimal("35.77"));
        e.setTxnRef("txn_1");
        e.setPaymentId("pay_1");
        e.setStatus(PaymentAuthorizationEntity.STATUS_CONFIRMED);
        e.setCreatedAt(Instant.parse("2026-06-15T00:00:00Z"));
        e.setExpiresAt(Instant.parse("2026-06-15T00:15:00Z"));
        e.setConfirmedAt(Instant.parse("2026-06-15T00:00:05Z"));
        return e;
    }

    @BeforeEach
    void setUp() {
        authorizationRepository = mock(PaymentAuthorizationRepository.class);
        ObjectMapper objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        PaymentController controller = new PaymentController(
                mock(PaymentOrchestrator.class), mock(PartnerConfigClient.class),
                authorizationRepository, mock(PaymentAuthorizationService.class),
                new com.gme.pay.events.RecordingEventPublisher());
        mvc = standaloneSetup(controller)
                .setControllerAdvice(new PaymentExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void getPayment_overseasConfirmed_returns200WithLowercaseStatusAndPrefund() throws Exception {
        when(authorizationRepository.findByPaymentIdAndPartnerId("pay_1", 101L))
                .thenReturn(Optional.of(confirmed("OVERSEAS")));

        mvc.perform(get("/v1/payments/pay_1").header("X-Partner-Id", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment_id").value("pay_1"))
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.prefund_deducted_usd").value(35.77))
                .andExpect(jsonPath("$.approved_at").value("2026-06-15T00:00:05Z"))
                .andExpect(jsonPath("$.cancelled_at").doesNotExist());
    }

    @Test
    void getPayment_localPartner_omitsPrefundField() throws Exception {
        when(authorizationRepository.findByPaymentIdAndPartnerId("pay_1", 101L))
                .thenReturn(Optional.of(confirmed("LOCAL")));

        mvc.perform(get("/v1/payments/pay_1").header("X-Partner-Id", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"))
                .andExpect(jsonPath("$.prefund_deducted_usd").doesNotExist());
    }

    @Test
    void getPayment_foreignOrMissing_returns404NotFound() throws Exception {
        // Repo is owner-scoped: a payment owned by partner 999 is invisible to partner 101 -> empty.
        when(authorizationRepository.findByPaymentIdAndPartnerId("pay_1", 101L))
                .thenReturn(Optional.empty());

        mvc.perform(get("/v1/payments/pay_1").header("X-Partner-Id", "101"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PAYMENT_NOT_FOUND"));
    }
}
