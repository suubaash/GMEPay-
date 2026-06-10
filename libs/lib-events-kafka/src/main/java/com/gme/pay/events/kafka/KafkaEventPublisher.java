package com.gme.pay.events.kafka;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Kafka-backed {@link EventPublisher} (ADR-001).
 *
 * <ul>
 *   <li><b>Topic naming:</b> {@code gmepay.<eventType>}, e.g. event type
 *       {@code payment.approved} is published to {@code gmepay.payment.approved}.</li>
 *   <li><b>Record key:</b> {@link DomainEvent#aggregateId()} — Kafka hashes the key to a
 *       partition, so all events of one aggregate are totally ordered.</li>
 *   <li><b>Payload:</b> the event serialized to JSON. Per {@code docs/MONEY_CONVENTION.md}
 *       every {@link BigDecimal} is written as a plain decimal <em>string</em>
 *       (e.g. {@code "10.20"}, never floating-point), and {@code java.time} values are
 *       ISO-8601 strings. The contract fields {@code eventType}, {@code aggregateId} and
 *       {@code occurredAt} are always present in the payload, regardless of the concrete
 *       event's bean shape.</li>
 *   <li><b>Delivery:</b> synchronous — {@code publish} blocks until the broker acks
 *       (producer is configured with {@code acks=all} + idempotence by the
 *       auto-configuration) or the timeout elapses. Any failure surfaces as
 *       {@link EventPublishException} so outbox callers do not mark rows published.</li>
 * </ul>
 */
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    /** Prefix for every GMEPay+ event topic. */
    public static final String TOPIC_PREFIX = "gmepay.";

    /** Default maximum time to wait for the broker ack before failing the publish. */
    public static final Duration DEFAULT_SEND_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Duration sendTimeout;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this(kafkaTemplate, defaultObjectMapper(), DEFAULT_SEND_TIMEOUT);
    }

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate, Duration sendTimeout) {
        this(kafkaTemplate, defaultObjectMapper(), sendTimeout);
    }

    public KafkaEventPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper objectMapper,
                               Duration sendTimeout) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.sendTimeout = Objects.requireNonNull(sendTimeout, "sendTimeout");
        if (sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("sendTimeout must be positive: " + sendTimeout);
        }
    }

    @Override
    public void publish(DomainEvent event) {
        Objects.requireNonNull(event, "event");
        String topic = topicName(event.eventType());
        String key = event.aggregateId();
        if (key == null || key.isBlank()) {
            throw new EventPublishException(
                    "DomainEvent.aggregateId() must be non-blank (it is the Kafka record key"
                            + " that guarantees per-aggregate ordering); eventType=" + event.eventType());
        }
        String payload = toJson(event);
        try {
            SendResult<String, String> result = kafkaTemplate.send(topic, key, payload)
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (log.isDebugEnabled()) {
                log.debug("published event: type={} aggregateId={} topic={} partition={} offset={}",
                        event.eventType(), key, topic,
                        result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException(
                    "Interrupted while publishing event type=" + event.eventType()
                            + " aggregateId=" + key + " to topic " + topic, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new EventPublishException(
                    "Failed to publish event type=" + event.eventType()
                            + " aggregateId=" + key + " to topic " + topic, cause);
        } catch (TimeoutException e) {
            throw new EventPublishException(
                    "Timed out after " + sendTimeout + " publishing event type=" + event.eventType()
                            + " aggregateId=" + key + " to topic " + topic, e);
        }
    }

    /**
     * Maps an event type to its Kafka topic: {@code gmepay.<eventType>}
     * (e.g. {@code payment.approved} → {@code gmepay.payment.approved}).
     */
    public static String topicName(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            throw new EventPublishException("DomainEvent.eventType() must be non-blank");
        }
        return TOPIC_PREFIX + eventType;
    }

    /**
     * Serializes the event to its JSON payload. Exposed package-private for tests.
     */
    String toJson(DomainEvent event) {
        try {
            JsonNode tree = objectMapper.valueToTree(event);
            ObjectNode node = tree instanceof ObjectNode objectNode
                    ? objectNode
                    : objectMapper.createObjectNode();
            // Contract fields are always present, whatever the concrete event's bean shape.
            node.put("eventType", event.eventType());
            node.put("aggregateId", event.aggregateId());
            Instant occurredAt = event.occurredAt();
            if (occurredAt == null) {
                node.putNull("occurredAt");
            } else {
                node.put("occurredAt", DateTimeFormatter.ISO_INSTANT.format(occurredAt));
            }
            return objectMapper.writeValueAsString(node);
        } catch (IllegalArgumentException | JsonProcessingException e) {
            throw new EventPublishException(
                    "Failed to serialize event type=" + event.eventType()
                            + " aggregateId=" + event.aggregateId(), e);
        }
    }

    /**
     * The canonical event payload mapper: {@link BigDecimal} as plain decimal string
     * (MONEY_CONVENTION — never scientific notation, never a JSON number) and
     * {@code java.time} values as ISO-8601 strings.
     */
    public static ObjectMapper defaultObjectMapper() {
        SimpleModule moneyModule = new SimpleModule("gmepay-money-as-plain-string");
        moneyModule.addSerializer(BigDecimal.class, new PlainStringBigDecimalSerializer());
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .addModule(moneyModule)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Events exposing only the DomainEvent interface methods have no bean
                // properties; serialize them as {} — toJson() fills in the contract fields.
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .build();
    }

    /** Writes {@code 10.20} as {@code "10.20"} — {@code toPlainString()} avoids scientific notation. */
    private static final class PlainStringBigDecimalSerializer extends JsonSerializer<BigDecimal> {
        @Override
        public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            gen.writeString(value.toPlainString());
        }
    }
}
