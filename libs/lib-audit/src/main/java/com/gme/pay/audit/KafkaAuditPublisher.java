package com.gme.pay.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * {@link AuditPublisher} that fans every event out to Kafka topic
 * {@code gmepay.audit.<aggregateType>}, the second tier of ADR-007's three-tier
 * audit architecture (the third tier — MinIO cold archive — is fed off this topic
 * by a Kafka Connect S3 sink configured in Slice 8 hardening).
 *
 * <p>Message key is the {@code aggregateId}, which means every event for the same
 * aggregate lands on the same partition and thus preserves per-aggregate ordering
 * (essential for the hash chain to remain replayable in cold storage).
 *
 * <p>Payload is JSON; hash bytes are rendered as lower-case hex so the cold archive
 * is human-readable for forensic spot-checks. (The hot DB still stores raw
 * {@code BYTEA} for compactness and exact byte comparison.)
 *
 * <p>Send failures log and swallow rather than rethrow: the hot DB row is the
 * regulator-defensible record, the Kafka tier is for cold-archive replay only, so a
 * transient broker outage must never roll back a business write. Production wiring
 * will sit this publisher behind the outbox so the topic message is only handed to
 * Kafka after commit (i.e. the at-least-once guarantee is in the outbox, not here).
 */
public final class KafkaAuditPublisher implements AuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaAuditPublisher.class);
    private static final HexFormat HEX = HexFormat.of();

    /** Topic prefix per ADR-007: {@code gmepay.audit.<aggregateType>}. */
    public static final String TOPIC_PREFIX = "gmepay.audit.";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public KafkaAuditPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this(kafkaTemplate, defaultObjectMapper());
    }

    /**
     * Overload that lets the consuming service supply its own pre-configured
     * {@link ObjectMapper}. Tests inject a mapper rather than letting the
     * default one allocate a fresh instance per call.
     */
    public KafkaAuditPublisher(KafkaTemplate<String, String> kafkaTemplate,
                               ObjectMapper objectMapper) {
        this.kafkaTemplate = Objects.requireNonNull(kafkaTemplate, "kafkaTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public void publish(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        String topic = TOPIC_PREFIX + event.aggregateType();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(toMap(event));
        } catch (JsonProcessingException e) {
            log.error("audit: failed to serialise event for topic={} aggregateId={}",
                    topic, event.aggregateId(), e);
            return; // never rethrow into the business write path
        }
        try {
            kafkaTemplate.send(topic, event.aggregateId(), payload);
        } catch (RuntimeException e) {
            log.error("audit: send failed for topic={} aggregateId={}",
                    topic, event.aggregateId(), e);
        }
    }

    private static Map<String, Object> toMap(AuditEvent event) {
        // Preserve a stable key order in the JSON envelope — same shape on every send,
        // which simplifies downstream consumers and the cold archive grep loop.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", event.id());
        m.put("aggregateType", event.aggregateType());
        m.put("aggregateId", event.aggregateId());
        m.put("actorId", event.actorId());
        m.put("actorIp", event.actorIp());
        m.put("eventType", event.eventType());
        m.put("beforeJsonb", event.beforeJsonb() == null ? null : new String(event.beforeJsonb(),
                java.nio.charset.StandardCharsets.UTF_8));
        m.put("afterJsonb", event.afterJsonb() == null ? null : new String(event.afterJsonb(),
                java.nio.charset.StandardCharsets.UTF_8));
        m.put("prevHash", event.prevHash() == null ? null : HEX.formatHex(event.prevHash()));
        m.put("rowHash", event.rowHash() == null ? null : HEX.formatHex(event.rowHash()));
        m.put("recordedAt", event.recordedAt() == null ? null : event.recordedAt().toString());
        return m;
    }

    private static ObjectMapper defaultObjectMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        m.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        return m;
    }
}
