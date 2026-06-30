package com.gme.pay.notify.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.notify.persistence.WebhookDeliveryEntity;
import com.gme.pay.notify.persistence.WebhookPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link PaymentApprovedEventHandler}: canonical
 * {@code PaymentApprovedPayload} deserialization, mapped-field carry-through onto
 * the persisted delivery payload, poison detection, the enqueue call contract and
 * the idempotent duplicate skip. No Spring, no broker — runs in the local `test` task.
 */
class PaymentApprovedEventHandlerTest {

    /** Canonical camelCase payload as payment-executor emits on gmepay.payment.approved. */
    private static final String VALID_PAYLOAD = """
            {"eventType":"payment.approved","aggregateId":"txn-0001","txnRef":"txn-0001",\
            "occurredAt":"2026-06-10T08:30:00Z","revenueDate":"2026-06-10","partnerId":42,"schemeId":7,\
            "collectionMarginUsd":"1.25","payoutMarginUsd":"0.75","serviceChargeAmount":"10.20",\
            "serviceChargeCcy":"USD","feeSharePct":"0.30"}""";

    private final ObjectMapper json = JsonMapper.builder().addModule(new JavaTimeModule()).build();

    private WebhookPersistenceService persistenceService;
    private PaymentApprovedEventHandler handler;

    @BeforeEach
    void setUp() {
        persistenceService = mock(WebhookPersistenceService.class);
        handler = new PaymentApprovedEventHandler(persistenceService);
    }

    @Test
    @DisplayName("valid payment.approved event enqueues a PENDING delivery keyed by txnRef")
    void validEvent_enqueues() {
        when(persistenceService.enqueuePendingIfAbsent(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new WebhookDeliveryEntity()));

        boolean enqueued = handler.handle("txn-0001", VALID_PAYLOAD);

        assertTrue(enqueued);
        verify(persistenceService).enqueuePendingIfAbsent(
                eq("txn-0001"), eq("payment.approved"), anyString());
    }

    @Test
    @DisplayName("delivery payload carries the approved-payment fields (txnRef, partnerId, schemeId, amounts)")
    void deliveryPayload_carriesMappedFields() throws Exception {
        when(persistenceService.enqueuePendingIfAbsent(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new WebhookDeliveryEntity()));

        handler.handle("txn-0001", VALID_PAYLOAD);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).enqueuePendingIfAbsent(
                eq("txn-0001"), eq("payment.approved"), payloadCaptor.capture());

        JsonNode delivered = json.readTree(payloadCaptor.getValue());
        assertEquals("payment.approved", delivered.get("eventType").asText());
        assertEquals("txn-0001", delivered.get("txnRef").asText());
        assertEquals(42, delivered.get("partnerId").asLong());
        assertEquals(7, delivered.get("schemeId").asLong());
        assertEquals("1.25", delivered.get("collectionMarginUsd").asText());
        assertEquals("0.75", delivered.get("payoutMarginUsd").asText());
        assertEquals("10.20", delivered.get("serviceChargeAmount").asText());
        assertEquals("USD", delivered.get("serviceChargeCcy").asText());
        assertEquals("0.30", delivered.get("feeSharePct").asText());
    }

    @Test
    @DisplayName("txnRef from the payload is the delivery key (wins over the record key)")
    void txnRefFromPayloadWins() {
        when(persistenceService.enqueuePendingIfAbsent(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new WebhookDeliveryEntity()));

        handler.handle("different-key", VALID_PAYLOAD);

        verify(persistenceService).enqueuePendingIfAbsent(
                eq("txn-0001"), eq("payment.approved"), any());
    }

    @Test
    @DisplayName("record key is the fallback when the payload has no txnRef/aggregateId")
    void recordKeyFallback() {
        when(persistenceService.enqueuePendingIfAbsent(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new WebhookDeliveryEntity()));

        handler.handle("txn-key-77", "{\"eventType\":\"payment.approved\"}");

        verify(persistenceService).enqueuePendingIfAbsent(
                eq("txn-key-77"), eq("payment.approved"), any());
    }

    @Test
    @DisplayName("duplicate event (already enqueued) is skipped without error")
    void duplicate_skippedWithoutError() {
        when(persistenceService.enqueuePendingIfAbsent(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertFalse(handler.handle("txn-0001", VALID_PAYLOAD),
                "duplicate must be a no-op so the consumer can still ack");
    }

    @ParameterizedTest(name = "poison payload [{0}] raises IllegalArgumentException")
    @ValueSource(strings = {
            "this-is-not-json",
            "{\"truncated\":",
            "[1,2,3]",
            "\"just a string\"",
            "{\"eventType\":\"payment.failed\",\"aggregateId\":\"txn-1\"}",
            "{\"aggregateId\":\"txn-1\"}"
    })
    void poisonPayloads_throw(String payload) {
        assertThrows(IllegalArgumentException.class, () -> handler.handle("key-1", payload));
        verifyNoInteractions(persistenceService);
    }

    @Test
    @DisplayName("unknown additive producer fields are tolerated (not poison)")
    void unknownFields_tolerated() {
        when(persistenceService.enqueuePendingIfAbsent(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new WebhookDeliveryEntity()));

        boolean enqueued = handler.handle("txn-0001",
                "{\"eventType\":\"payment.approved\",\"txnRef\":\"txn-0001\",\"futureField\":\"x\"}");

        assertTrue(enqueued);
    }

    @Test
    @DisplayName("blank payload is poison")
    void blankPayload_throws() {
        assertThrows(IllegalArgumentException.class, () -> handler.handle("key-1", "   "));
        assertThrows(IllegalArgumentException.class, () -> handler.handle("key-1", null));
        verifyNoInteractions(persistenceService);
    }

    @Test
    @DisplayName("no transaction reference anywhere (payload or key) is poison")
    void missingTxnRefEverywhere_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> handler.handle(null, "{\"eventType\":\"payment.approved\"}"));
        verifyNoInteractions(persistenceService);
    }

    @Test
    @DisplayName("txnRef longer than the webhook_id column (64) is poison")
    void oversizedTxnRef_throws() {
        String oversized = "x".repeat(65);
        assertThrows(IllegalArgumentException.class, () -> handler.handle(
                null,
                "{\"eventType\":\"payment.approved\",\"txnRef\":\"" + oversized + "\"}"));
        verifyNoInteractions(persistenceService);
    }

    @Test
    @DisplayName("topic and DLT constants follow the gmepay.<eventType> convention")
    void topicConstants() {
        assertEquals("gmepay.payment.approved", PaymentApprovedKafkaConsumer.TOPIC);
        assertEquals("gmepay.payment.approved.DLT", PaymentApprovedKafkaConsumer.DLT_TOPIC);
        assertEquals("notification-webhook", PaymentApprovedKafkaConsumer.GROUP_ID);
    }
}
