package com.gme.pay.events.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests — no broker, no Docker, no Mockito. {@link RecordingKafkaTemplate} is a
 * hand-rolled {@link KafkaTemplate} subclass that captures sends and returns a scripted
 * future (the producer factory is never asked to create a real producer because
 * {@code send} is overridden).
 */
class KafkaEventPublisherTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Fake template: records every send, replies with {@link #nextResult}. */
    private static final class RecordingKafkaTemplate extends KafkaTemplate<String, String> {

        final List<ProducerRecord<String, String>> sent = new ArrayList<>();
        CompletableFuture<SendResult<String, String>> nextResult; // null => succeed immediately

        RecordingKafkaTemplate() {
            super(new DefaultKafkaProducerFactory<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1",
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)));
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(String topic, String key, String data) {
            ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, data);
            sent.add(record);
            if (nextResult != null) {
                return nextResult;
            }
            RecordMetadata metadata = new RecordMetadata(new TopicPartition(topic, 0), 0L, 0, 0L, 0, 0);
            return CompletableFuture.completedFuture(new SendResult<>(record, metadata));
        }
    }

    private final RecordingKafkaTemplate template = new RecordingKafkaTemplate();
    private final KafkaEventPublisher publisher = new KafkaEventPublisher(template);

    // ---------------------------------------------------------------- topic + key

    @Test
    @DisplayName("topic is 'gmepay.' + eventType (payment.approved -> gmepay.payment.approved)")
    void topicNameMapping() {
        publisher.publish(PaymentApprovedTestEvent.sample());

        assertEquals(1, template.sent.size());
        assertEquals("gmepay.payment.approved", template.sent.get(0).topic());
        assertEquals("gmepay.prefunding.balance.low", KafkaEventPublisher.topicName("prefunding.balance.low"));
    }

    @Test
    @DisplayName("record key is the aggregateId (per-aggregate partition ordering)")
    void recordKeyIsAggregateId() {
        publisher.publish(PaymentApprovedTestEvent.sample());

        assertEquals("txn-0001", template.sent.get(0).key());
    }

    @Test
    @DisplayName("blank eventType is rejected before anything is sent")
    void blankEventTypeRejected() {
        PaymentApprovedTestEvent event = new PaymentApprovedTestEvent(
                "  ", "txn-1", Instant.now(), new BigDecimal("1.00"), "USD");

        assertThrows(EventPublishException.class, () -> publisher.publish(event));
        assertTrue(template.sent.isEmpty(), "nothing must reach Kafka");
    }

    @Test
    @DisplayName("blank aggregateId is rejected before anything is sent (key is mandatory for ordering)")
    void blankAggregateIdRejected() {
        PaymentApprovedTestEvent event = new PaymentApprovedTestEvent(
                "payment.approved", "", Instant.now(), new BigDecimal("1.00"), "USD");

        assertThrows(EventPublishException.class, () -> publisher.publish(event));
        assertTrue(template.sent.isEmpty(), "nothing must reach Kafka");
    }

    // ---------------------------------------------------------------- JSON shape

    @Test
    @DisplayName("payload JSON: BigDecimal as plain decimal string, occurredAt as ISO-8601 string")
    void jsonShape() throws Exception {
        publisher.publish(PaymentApprovedTestEvent.sample());

        JsonNode payload = JSON.readTree(template.sent.get(0).value());

        assertEquals("payment.approved", payload.get("eventType").asText());
        assertEquals("txn-0001", payload.get("aggregateId").asText());
        assertEquals("2026-06-10T08:30:00Z", payload.get("occurredAt").asText());
        assertTrue(payload.get("occurredAt").isTextual(), "occurredAt must be an ISO string, not a timestamp");

        JsonNode amount = payload.get("amount");
        assertNotNull(amount);
        assertTrue(amount.isTextual(), "money must be a JSON string per MONEY_CONVENTION, got: " + amount);
        assertEquals("10.20", amount.asText());
        assertEquals("USD", payload.get("currency").asText());
    }

    @Test
    @DisplayName("BigDecimal serialization never uses scientific notation and keeps scale")
    void bigDecimalPlainString() throws Exception {
        publisher.publish(new PaymentApprovedTestEvent(
                "payment.approved", "txn-2", Instant.parse("2026-06-10T00:00:00Z"),
                new BigDecimal("5E+4"), "KRW"));
        publisher.publish(new PaymentApprovedTestEvent(
                "payment.approved", "txn-3", Instant.parse("2026-06-10T00:00:00Z"),
                new BigDecimal("0.10"), "USD"));

        assertEquals("50000", JSON.readTree(template.sent.get(0).value()).get("amount").asText());
        assertEquals("0.10", JSON.readTree(template.sent.get(1).value()).get("amount").asText());
    }

    @Test
    @DisplayName("contract fields are present even for a non-bean DomainEvent implementation")
    void contractFieldsAlwaysPresent() throws Exception {
        // Anonymous class: eventType()/aggregateId()/occurredAt() are NOT bean getters,
        // so plain Jackson would emit {} — the publisher must still write the contract fields.
        publisher.publish(new com.gme.pay.events.DomainEvent() {
            @Override public String eventType()   { return "qr.generated"; }
            @Override public String aggregateId() { return "qr-77"; }
            @Override public Instant occurredAt() { return Instant.parse("2026-06-10T01:02:03Z"); }
        });

        JsonNode payload = JSON.readTree(template.sent.get(0).value());
        assertEquals("qr.generated", payload.get("eventType").asText());
        assertEquals("qr-77", payload.get("aggregateId").asText());
        assertEquals("2026-06-10T01:02:03Z", payload.get("occurredAt").asText());
        assertEquals("gmepay.qr.generated", template.sent.get(0).topic());
    }

    // ---------------------------------------------------------------- failure handling

    @Test
    @DisplayName("broker failure surfaces as EventPublishException with the Kafka cause preserved")
    void brokerFailurePropagates() {
        KafkaException boom = new KafkaException("broker unavailable");
        template.nextResult = CompletableFuture.failedFuture(boom);

        EventPublishException ex = assertThrows(EventPublishException.class,
                () -> publisher.publish(PaymentApprovedTestEvent.sample()));

        assertSame(boom, ex.getCause(), "original Kafka cause must be preserved for diagnostics");
        assertTrue(ex.getMessage().contains("gmepay.payment.approved"));
        assertTrue(ex.getMessage().contains("txn-0001"));
    }

    @Test
    @DisplayName("send that never completes fails with EventPublishException after the timeout")
    void sendTimeoutPropagates() {
        template.nextResult = new CompletableFuture<>(); // never completes
        KafkaEventPublisher impatient = new KafkaEventPublisher(template, Duration.ofMillis(50));

        EventPublishException ex = assertThrows(EventPublishException.class,
                () -> impatient.publish(PaymentApprovedTestEvent.sample()));

        assertInstanceOf(java.util.concurrent.TimeoutException.class, ex.getCause());
    }

    @Test
    @DisplayName("EventPublishException is unchecked so outbox callers fail the row, not swallow it")
    void publishExceptionIsRuntime() {
        assertTrue(RuntimeException.class.isAssignableFrom(EventPublishException.class));
    }

    @Test
    @DisplayName("null event is rejected")
    void nullEventRejected() {
        assertThrows(NullPointerException.class, () -> publisher.publish(null));
    }
}
