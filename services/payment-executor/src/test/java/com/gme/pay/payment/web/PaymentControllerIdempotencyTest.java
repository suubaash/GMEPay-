package com.gme.pay.payment.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.payment.domain.PartnerType;
import com.gme.pay.payment.domain.PaymentOrchestrator;
import com.gme.pay.payment.domain.PaymentOrchestrator.MpmPaymentCommand;
import com.gme.pay.payment.domain.PaymentOrchestrator.PaymentResult;
import com.gme.pay.payment.domain.PaymentStatus;
import com.gme.pay.payment.domain.client.PartnerConfigClient;
import com.gme.pay.payment.domain.client.PartnerConfigClient.PartnerConfigView;
import com.gme.pay.payment.persistence.IdempotencyRecordEntity;
import com.gme.pay.payment.persistence.IdempotencyRecordRepository;
import com.gme.pay.payment.web.dto.MpmPaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
 * Standalone MockMvc tests for the POST /v1/payments idempotency replay and the
 * config-registry partner-type resolution (header fallback). Orchestrator and
 * repositories are mocked so only the controller wiring is under test.
 */
class PaymentControllerIdempotencyTest {

    private static final String BODY = """
            {"quote_id":"qte_1","merchant_qr":"ZPQR1","direction":"inbound","scheme_id":"zeropay",
             "customer_ref":"c1","partner_txn_ref":"PTR1","collection_amount":"1000",
             "collection_currency":"KRW","country_code":"KR"}
            """;

    private PaymentOrchestrator orchestrator;
    private IdempotencyRecordRepository idempotencyRepository;
    private PartnerConfigClient partnerConfigClient;
    private ObjectMapper objectMapper;
    private MockMvc mvc;

    private static PaymentResult sampleResult(String paymentId) {
        return new PaymentResult(
                paymentId, PaymentStatus.APPROVED, "ZP_TXN_1", "Cafe", "M-1",
                new BigDecimal("1000"), "KRW", null,
                new BigDecimal("1000"), "KRW", new BigDecimal("500"), "KRW", null,
                "PTR1", Instant.parse("2026-06-15T00:00:00Z"), Instant.parse("2026-06-15T00:00:01Z"));
    }

    @BeforeEach
    void setUp() {
        orchestrator = mock(PaymentOrchestrator.class);
        idempotencyRepository = mock(IdempotencyRecordRepository.class);
        partnerConfigClient = mock(PartnerConfigClient.class);
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        PaymentController controller = new PaymentController(
                orchestrator, partnerConfigClient, idempotencyRepository, objectMapper);
        mvc = standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void firstCall_executesAndRecordsIdempotencyKey() throws Exception {
        when(idempotencyRepository.findByPartnerIdAndIdempotencyKey(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(orchestrator.executeMpm(any(MpmPaymentCommand.class), any(PartnerType.class)))
                .thenReturn(sampleResult("pay_1"));

        mvc.perform(post("/v1/payments")
                        .header("Idempotency-Key", "key-1")
                        .header("X-Partner-Id", "1")
                        .header("X-Partner-Type", "LOCAL")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment_id").value("pay_1"));

        verify(orchestrator).executeMpm(any(), eq(PartnerType.LOCAL));
        verify(idempotencyRepository).save(any(IdempotencyRecordEntity.class));
    }

    @Test
    void replay_returnsCachedResponseWithoutReexecuting() throws Exception {
        IdempotencyRecordEntity rec = new IdempotencyRecordEntity(1L, "key-1", "hash", Instant.now());
        rec.recordOutcome(PaymentStatus.APPROVED, objectMapper.writeValueAsString(
                new MpmPaymentResponse(
                        "pay_cached", "approved", "ZP_TXN_1", "Cafe", "M-1",
                        new BigDecimal("1000"), "KRW", null,
                        new BigDecimal("1000"), "KRW", new BigDecimal("500"), "KRW", null,
                        "PTR1", Instant.parse("2026-06-15T00:00:00Z"),
                        Instant.parse("2026-06-15T00:00:01Z"))));
        when(idempotencyRepository.findByPartnerIdAndIdempotencyKey(1L, "key-1"))
                .thenReturn(Optional.of(rec));

        mvc.perform(post("/v1/payments")
                        .header("Idempotency-Key", "key-1")
                        .header("X-Partner-Id", "1")
                        .header("X-Partner-Type", "LOCAL")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payment_id").value("pay_cached"));

        verify(orchestrator, never()).executeMpm(any(), any());
    }

    @Test
    void resolvesPartnerTypeFromConfigRegistryByCode_overridingHeader() throws Exception {
        when(idempotencyRepository.findByPartnerIdAndIdempotencyKey(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(partnerConfigClient.loadPartner("GMEREMIT"))
                .thenReturn(new PartnerConfigView("GMEREMIT", "LOCAL", "KRW", RoundingMode.HALF_UP));
        when(orchestrator.executeMpm(any(), any())).thenReturn(sampleResult("pay_2"));

        mvc.perform(post("/v1/payments")
                        .header("X-Partner-Id", "1")
                        .header("X-Partner-Code", "GMEREMIT")
                        .header("X-Partner-Type", "OVERSEAS") // config (LOCAL) must win over this header
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated());

        verify(partnerConfigClient).loadPartner("GMEREMIT");
        verify(orchestrator).executeMpm(any(), eq(PartnerType.LOCAL));
    }
}
