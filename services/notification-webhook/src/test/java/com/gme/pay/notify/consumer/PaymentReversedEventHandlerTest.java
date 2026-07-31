package com.gme.pay.notify.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.gme.pay.contracts.events.PaymentReversedPayload;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Gap T2-6: {@code notification-webhook} handled only {@code payment.approved}, so a partner was told when a
 * payment succeeded and <b>never told when it was refunded or reversed</b>. These tests pin the new
 * {@code payment.reversed} producer of delivery rows.
 *
 * <p>No signing is asserted here on purpose: this handler enqueues a row and the EXISTING dispatcher +
 * {@code DefaultWebhookTargetResolver} sign it with that endpoint's own derived secret, unchanged from T5-4.
 * What this must prove instead is that the enqueued payload still carries the {@code partnerId} the resolver
 * needs to find the endpoint and derive its secret — see
 * {@link #deliveryPayloadCarriesPartnerIdForTheResolver()}.
 */
class PaymentReversedEventHandlerTest {

    /** Canonical camelCase payload as transaction-mgmt emits on gmepay.payment.reversed. */
    private static final String VALID_PAYLOAD = """
            {"eventType":"payment.reversed","txnRef":"txn-0001","partnerId":"42","schemeId":"zeropay",\
            "reversedAmount":"20000","currency":"KRW","reversedUsd":"15.0000","reason":"CUSTOMER_REQUEST",\
            "source":"REFUND","occurredAt":"2026-07-28T08:30:00Z"}""";

    private final ObjectMapper json = JsonMapper.builder().build();

    private WebhookPersistenceService persistenceService;
    private PaymentReversedEventHandler handler;

    @BeforeEach
    void setUp() {
        persistenceService = mock(WebhookPersistenceService.class);
        handler = new PaymentReversedEventHandler(persistenceService);
    }

    private void enqueueSucceeds() {
        when(persistenceService.enqueuePendingIfAbsent(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new WebhookDeliveryEntity()));
    }

    @Test
    @DisplayName("a refund event enqueues a PENDING delivery keyed by txnRef under eventType payment.reversed")
    void validEvent_enqueues() {
        enqueueSucceeds();

        assertTrue(handler.handle("txn-0001", VALID_PAYLOAD),
                "the partner must get a delivery row for a refund, which is what never existed");

        ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> type = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).enqueuePendingIfAbsent(id.capture(), type.capture(), body.capture());

        assertEquals("txn-0001", id.getValue());
        // The event type is what keys the row apart from the approval delivery for the SAME transaction, so a
        // refund does not collide with (or get skipped by) the approval that preceded it.
        assertEquals(PaymentReversedPayload.EVENT_TYPE, type.getValue());
        assertEquals("payment.reversed", type.getValue());
    }

    @Test
    @DisplayName("the delivery payload carries partnerId, so the dispatcher can resolve + sign for the endpoint")
    void deliveryPayloadCarriesPartnerIdForTheResolver() throws Exception {
        enqueueSucceeds();
        handler.handle("txn-0001", VALID_PAYLOAD);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(persistenceService).enqueuePendingIfAbsent(anyString(), anyString(), body.capture());
        JsonNode node = json.readTree(body.getValue());

        // DefaultWebhookTargetResolver reads partnerId from THIS body to find the endpoint and re-derive its
        // per-endpoint signing secret. Lose it and the row is undeliverable — so it is asserted, not assumed.
        assertEquals("42", node.get("partnerId").asText());
        // The refund facts a partner needs, in the canonical shape.
        assertEquals("payment.reversed", node.get("eventType").asText());
        assertEquals("txn-0001", node.get("txnRef").asText());
        assertEquals("20000", node.get("reversedAmount").asText());
        assertEquals("KRW", node.get("currency").asText());
        assertEquals("REFUND", node.get("source").asText());
        assertEquals("CUSTOMER_REQUEST", node.get("reason").asText());
    }

    @Test
    @DisplayName("an operator force-resolve reversal is delivered on the same contract")
    void operatorReversal_isAlsoDelivered() {
        enqueueSucceeds();
        String operatorPayload = VALID_PAYLOAD.replace("\"source\":\"REFUND\"", "\"source\":\"OPERATOR\"");

        assertTrue(handler.handle("txn-0001", operatorPayload));
        verify(persistenceService).enqueuePendingIfAbsent(
                anyString(), org.mockito.ArgumentMatchers.eq(PaymentReversedPayload.EVENT_TYPE), anyString());
    }

    @Test
    @DisplayName("a redelivered reversal is an idempotent skip, not an error")
    void duplicateEvent_isSkipped() {
        when(persistenceService.enqueuePendingIfAbsent(anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertFalse(handler.handle("txn-0001", VALID_PAYLOAD),
                "Kafka is at-least-once; a second delivery must not send the partner a second refund webhook");
    }

    @Test
    @DisplayName("the record key is the txnRef fallback when the payload omits one")
    void missingTxnRef_fallsBackToTheRecordKey() {
        enqueueSucceeds();
        String noTxnRef = VALID_PAYLOAD.replace("\"txnRef\":\"txn-0001\",", "");

        assertTrue(handler.handle("txn-from-key", noTxnRef));
        verify(persistenceService).enqueuePendingIfAbsent(
                org.mockito.ArgumentMatchers.eq("txn-from-key"), anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "not json", "{\"eventType\":\"payment.approved\"}",
            "{\"eventType\":\"payment.reversed\"}"})
    @DisplayName("poison records raise IllegalArgumentException and enqueue nothing")
    void poisonRecords_areRejected(String payload) {
        assertThrows(IllegalArgumentException.class, () -> handler.handle(null, payload));
        verifyNoInteractions(persistenceService);
    }

    @Test
    @DisplayName("a null payload is poison, not a silent no-op")
    void nullPayload_isPoison() {
        assertThrows(IllegalArgumentException.class, () -> handler.handle("txn-0001", null));
        verifyNoInteractions(persistenceService);
    }

    @Test
    @DisplayName("an over-long transaction reference is rejected rather than truncated into the column")
    void overLongReference_isRejected() {
        String longRef = "x".repeat(PaymentApprovedEventHandler.MAX_WEBHOOK_ID_LENGTH + 1);
        String payload = VALID_PAYLOAD.replace("txn-0001", longRef);

        assertThrows(IllegalArgumentException.class, () -> handler.handle(longRef, payload));
        verifyNoInteractions(persistenceService);
    }

    @Test
    @DisplayName("the consumer's topic and group match the approvals listener's wiring")
    void consumerWiring() {
        assertEquals("gmepay.payment.reversed", PaymentReversedKafkaConsumer.TOPIC);
        assertEquals("gmepay.payment.reversed.DLT", PaymentReversedKafkaConsumer.DLT_TOPIC);
        assertEquals(PaymentApprovedKafkaConsumer.GROUP_ID, PaymentReversedKafkaConsumer.GROUP_ID,
                "one notification-webhook consumer group across both topics");
    }
}
