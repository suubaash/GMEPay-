package com.gme.pay.notify.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the MANUAL-ack contract of {@link PaymentApprovedKafkaConsumer}:
 * the offset is acknowledged only after the handler has recorded the delivery;
 * a throwing handler must leave the record un-acked (the container error handler
 * then retries / dead-letters it).
 */
class PaymentApprovedKafkaConsumerTest {

    private final PaymentApprovedEventHandler handler = mock(PaymentApprovedEventHandler.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final PaymentApprovedKafkaConsumer consumer = new PaymentApprovedKafkaConsumer(handler);

    private static ConsumerRecord<String, String> record(String key, String value) {
        return new ConsumerRecord<>(PaymentApprovedKafkaConsumer.TOPIC, 0, 0L, key, value);
    }

    @Test
    @DisplayName("acks only after the handler has recorded the delivery attempt")
    void acksAfterHandlerSucceeds() {
        when(handler.handle(anyString(), anyString())).thenReturn(true);

        consumer.onPaymentApproved(record("txn-1", "{}"), ack);

        var inOrder = org.mockito.Mockito.inOrder(handler, ack);
        inOrder.verify(handler).handle("txn-1", "{}");
        inOrder.verify(ack).acknowledge();
    }

    @Test
    @DisplayName("duplicate (handler returns false) is still acked — done, nothing to redo")
    void acksOnDuplicateSkip() {
        when(handler.handle(anyString(), anyString())).thenReturn(false);

        consumer.onPaymentApproved(record("txn-1", "{}"), ack);

        verify(ack).acknowledge();
    }

    @Test
    @DisplayName("does NOT ack when the handler throws — error handler takes over")
    void noAckWhenHandlerThrows() {
        when(handler.handle(any(), any())).thenThrow(new IllegalArgumentException("poison"));

        assertThrows(IllegalArgumentException.class,
                () -> consumer.onPaymentApproved(record("txn-1", "garbage"), ack));

        verifyNoInteractions(ack);
    }
}
