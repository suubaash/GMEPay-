package com.gme.pay.notify.consumer;

import com.gme.pay.notify.persistence.WebhookDeliveryEntity;
import com.gme.pay.notify.persistence.WebhookPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
 * Pure unit tests for {@link PaymentApprovedEventHandler}: payload validation
 * (poison detection), enqueue call contract and idempotent duplicate skip.
 * No Spring, no broker — runs in the local `test` task.
 */
class PaymentApprovedEventHandlerTest {

    private static final String VALID_PAYLOAD = """
            {"eventType":"payment.approved","aggregateId":"txn-0001",\
            "occurredAt":"2026-06-10T08:30:00Z","amount":"10.20","currency":"USD"}""";

    private WebhookPersistenceService persistenceService;
    private PaymentApprovedEventHandler handler;

    @BeforeEach
    void setUp() {
        persistenceService = mock(WebhookPersistenceService.class);
        handler = new PaymentApprovedEventHandler(persistenceService);
    }

    @Test
    @DisplayName("valid payment.approved event enqueues a PENDING delivery keyed by aggregateId")
    void validEvent_enqueues() {
        when(persistenceService.enqueuePendingIfAbsent(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new WebhookDeliveryEntity()));

        boolean enqueued = handler.handle("txn-0001", VALID_PAYLOAD);

        assertTrue(enqueued);
        verify(persistenceService).enqueuePendingIfAbsent(
                eq("txn-0001"), eq("payment.approved"), eq(VALID_PAYLOAD));
    }

    @Test
    @DisplayName("aggregateId from the payload wins over the record key")
    void aggregateIdFromPayloadWins() {
        when(persistenceService.enqueuePendingIfAbsent(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new WebhookDeliveryEntity()));

        handler.handle("different-key", VALID_PAYLOAD);

        verify(persistenceService).enqueuePendingIfAbsent(
                eq("txn-0001"), eq("payment.approved"), any());
    }

    @Test
    @DisplayName("record key is the fallback when the payload has no aggregateId")
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
    @DisplayName("blank payload is poison")
    void blankPayload_throws() {
        assertThrows(IllegalArgumentException.class, () -> handler.handle("key-1", "   "));
        assertThrows(IllegalArgumentException.class, () -> handler.handle("key-1", null));
        verifyNoInteractions(persistenceService);
    }

    @Test
    @DisplayName("no aggregateId anywhere (payload or key) is poison")
    void missingAggregateIdEverywhere_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> handler.handle(null, "{\"eventType\":\"payment.approved\"}"));
        verifyNoInteractions(persistenceService);
    }

    @Test
    @DisplayName("aggregateId longer than the webhook_id column (64) is poison")
    void oversizedAggregateId_throws() {
        String oversized = "x".repeat(65);
        assertThrows(IllegalArgumentException.class, () -> handler.handle(
                null,
                "{\"eventType\":\"payment.approved\",\"aggregateId\":\"" + oversized + "\"}"));
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
