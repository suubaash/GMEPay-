package com.gme.pay.txn.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.events.kafka.KafkaEventPublisher;
import com.gme.pay.txn.domain.model.TransactionStatus;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Docker-backed IT for the transaction-mgmt outbox -&gt; Kafka drain (ticket 17.4-G02).
 *
 * <p>Boots the full Spring context against a postgres:16 container (Flyway V001+V002) with
 * {@code spring.kafka.bootstrap-servers} pointing at an apache/kafka container. That activates
 * {@code KafkaEventPublisherAutoConfiguration} (libs/lib-events-kafka) so
 * {@link OutboxConfig} wires {@link KafkaEventPublisher} into the {@link OutboxPublisher}
 * transport — exactly as it would under docker-compose ({@code SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092}).
 *
 * <p>Verified contract (happy path):
 * <ol>
 *   <li>{@link OutboxAppender} persists a row in the caller's DB transaction (no broker call);
 *   <li>{@link OutboxPublisher#publishPending()} drains the row through the Kafka transport;
 *   <li>the event lands on topic {@code gmepay.<eventType>} with record key = aggregateId;
 *   <li>only after the broker ack does {@code published_at} stamp on the row.
 * </ol>
 *
 * <p>The failure path (broker unreachable -&gt; row stays unpublished) lives in
 * {@code OutboxKafkaUnreachableIT} so the two cases boot independent contexts with their own
 * {@code spring.kafka.bootstrap-servers} value.
 *
 * <p>{@code @Tag("docker")}: excluded from the local {@code test} task (this Windows box has
 * no Docker); CI runs it via {@code integrationTest}. {@code disabledWithoutDocker = true}
 * self-skips defensively if Docker is unavailable.
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
// Push the @Scheduled tick out so it cannot race the explicit publishPending() call.
// The initial on-startup tick is harmless: the outbox in the fresh PG container is empty
// until this test appends its row.
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
class OutboxKafkaPublishIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @Container
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // application.properties no longer pins the H2 dialect — Hibernate auto-detects from
        // the JDBC connection metadata, so no override needed here for PostgreSQL.
        // Standard Spring property — same one docker-compose feeds via SPRING_KAFKA_BOOTSTRAP_SERVERS.
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private OutboxAppender outboxAppender;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private EventPublisher eventPublisher;

    @Test
    @DisplayName("appended row drains to gmepay.<eventType>; published_at stamped only after broker ack")
    void appendedRowDrainsToKafkaAndIsMarkedPublishedAfterAck() throws Exception {
        // 0. With bootstrap-servers set, the @Primary by-type publisher must be the Kafka one
        //    (synchronous, acks=all) — NOT the LoggingEventPublisher fallback. OutboxConfig
        //    selects the same transport for the OutboxPublisher drain.
        assertInstanceOf(KafkaEventPublisher.class, eventPublisher,
                "spring.kafka.bootstrap-servers is set, so the auto-configured @Primary "
                        + "KafkaEventPublisher must win over LoggingEventPublisher");

        // 1. Append. OutboxAppender persists via OutboxRepository.save (Spring Data opens its
        //    own JPA transaction around it when no outer tx is active — the production caller,
        //    TransactionStateMachine, runs inside a controller's @Transactional, but here we
        //    exercise the appender directly and the auto-tx commits the row before we drain.
        String txnRef = "TXN-OUTBOX-IT-" + UUID.randomUUID();
        TransactionStatusChangedEvent event = new TransactionStatusChangedEvent(
                txnRef, TransactionStatus.CREATED, TransactionStatus.PENDING_DEBIT);
        outboxAppender.publish(event);

        OutboxEntity ours = outboxRepository.findUnpublished(PageRequest.of(0, 1000)).stream()
                .filter(o -> txnRef.equals(o.getAggregateId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected an unpublished outbox row for txnRef " + txnRef));
        assertEquals("TransactionStatusChanged", ours.getEventType());
        assertNull(ours.getPublishedAt(), "row must start unpublished (broker not yet contacted)");

        // 2. Drain. KafkaEventPublisher.publish blocks until the broker acks; only a successful
        //    return lets OutboxPublisher stamp published_at.
        outboxPublisher.publishPending();

        // 3. The event is consumable from the real broker on topic gmepay.<eventType> with
        //    record key = aggregateId (per-aggregate ordering contract).
        ConsumerRecord<String, String> record =
                consumeOneWithKey("gmepay.TransactionStatusChanged", txnRef);
        assertEquals("gmepay.TransactionStatusChanged", record.topic());
        assertEquals(txnRef, record.key(),
                "record key must be the aggregateId (txnRef) for per-aggregate ordering");

        JsonNode payload = new ObjectMapper().readTree(record.value());
        assertEquals("TransactionStatusChanged", payload.get("eventType").asText());
        assertEquals(txnRef, payload.get("aggregateId").asText());
        assertNotNull(payload.get("occurredAt"), "payload must carry occurredAt");
        assertEquals(ours.getCreatedAt(), Instant.parse(payload.get("occurredAt").asText()),
                "occurredAt must be the outbox enqueue time (createdAt), not the publish time");

        // 4. Only now — after the synchronous, acked publish — is the row marked published.
        OutboxEntity reread = outboxRepository.findById(ours.getId()).orElseThrow();
        assertNotNull(reread.getPublishedAt(),
                "published_at must be stamped once the broker has acked the record");

        // 5. And no unpublished lag remains for this aggregate.
        assertEquals(0L, outboxRepository.findUnpublished(PageRequest.of(0, 1000)).stream()
                        .filter(o -> txnRef.equals(o.getAggregateId()))
                        .count(),
                "drained row must no longer be returned by findUnpublished");
    }

    /**
     * Polls {@code topic} from the earliest offset for up to 30s and returns the first record
     * whose key matches {@code expectedKey} (isolates this test's event from any other rows
     * the context may have drained, e.g. from the harmless on-startup @Scheduled tick).
     */
    private static ConsumerRecord<String, String> consumeOneWithKey(String topic, String expectedKey) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "gmepay-txn-outbox-it-" + System.nanoTime());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (expectedKey.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        return fail("no record with key " + expectedKey + " received on topic " + topic + " within 30s");
    }
}
