package com.gme.pay.ledger.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.events.kafka.KafkaEventPublisher;
import com.gme.pay.ledger.domain.ledger.LedgerPostingService;
import com.gme.pay.ledger.domain.model.Journal;
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

import java.math.BigDecimal;
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
 * Docker-backed IT for <b>17.4-G03</b>: the transactional outbox drains {@code journal.posted}
 * rows to the real Kafka topic {@code gmepay.journal.posted}.
 *
 * <p>Boots the FULL application context against a postgres:16 container (Flyway V001..V003)
 * with {@code spring.kafka.bootstrap-servers} pointing at an apache/kafka container, so
 * {@code KafkaEventPublisherAutoConfiguration} (libs/lib-events-kafka) activates exactly as it
 * does under docker-compose ({@code SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092}).</p>
 *
 * <p>Verified contract:</p>
 * <ol>
 *   <li>the active {@link EventPublisher} is the @Primary {@link KafkaEventPublisher} — the
 *       broker-ack-gated, synchronous publisher (acks=all + idempotence);</li>
 *   <li>saving a journal enqueues an unpublished outbox row ({@code published_at IS NULL});</li>
 *   <li>{@link OutboxPublisher#publishPending()} publishes the event to topic
 *       {@code gmepay.journal.posted} with record key = journalId (aggregateId) and only then
 *       stamps {@code published_at}. The "only after ack" half: {@link KafkaEventPublisher}
 *       blocks on the broker ack and throws {@code EventPublishException} on failure, in which
 *       case {@link OutboxPublisher} leaves the row unpublished for retry (failure semantics
 *       are unit-covered in {@code OutboxPublisherRetryTest}).</li>
 * </ol>
 *
 * <p>{@code @Tag("docker")}: excluded from the local {@code test} task (this machine has no
 * Docker); CI runs it via {@code integrationTest}. {@code disabledWithoutDocker = true}
 * self-skips defensively on Docker-less hosts.</p>
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
// Poll interval pushed out to 1 hour so the @Scheduled tick cannot race the explicit
// publishPending() call. The initial on-startup tick is harmless: the outbox in the fresh
// PG container is empty until this test posts its journal.
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
class OutboxKafkaDrainIT {

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
        // application.properties pins the H2 dialect for the no-docker unit slices; override for PG.
        registry.add("spring.jpa.properties.hibernate.dialect",
                () -> "org.hibernate.dialect.PostgreSQLDialect");
        // Standard Spring property — same one docker-compose feeds via SPRING_KAFKA_BOOTSTRAP_SERVERS.
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private EventPublisher eventPublisher;

    @Test
    @DisplayName("outbox journal.posted row drains to topic gmepay.journal.posted; published_at stamped only after broker ack")
    void outboxRowDrainsToKafkaTopicAndIsMarkedPublishedAfterAck() throws Exception {
        // 0. With bootstrap-servers set, the @Primary publisher must be the Kafka-backed one
        //    (synchronous, acks=all) — NOT the LogEventPublisher fallback.
        assertInstanceOf(KafkaEventPublisher.class, eventPublisher,
                "spring.kafka.bootstrap-servers is set, so the auto-configured @Primary "
                        + "KafkaEventPublisher must win over LogEventPublisher");

        // 1. Post a journal — JpaJournalStore.save enqueues the outbox row in the SAME txn.
        String ref = "TXN-KAFKA-IT-" + UUID.randomUUID();
        Journal journal = ledgerPostingService.postRevenueCapture(
                ref, new BigDecimal("12.3400"), new BigDecimal("500"), "KRW");

        OutboxEntity ours = outboxRepository.findUnpublished(PageRequest.of(0, 1000)).stream()
                .filter(o -> journal.journalId().equals(o.getAggregateId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected an unpublished outbox row for journal " + journal.journalId()));
        assertEquals("journal.posted", ours.getEventType());
        assertNull(ours.getPublishedAt(), "row must start unpublished (broker not yet acked)");

        // 2. Drain. KafkaEventPublisher.publish blocks until the broker acks; only a successful
        //    return lets OutboxPublisher stamp published_at.
        outboxPublisher.publishPending();

        // 3. The event is consumable from the real broker on topic gmepay.<eventType>.
        ConsumerRecord<String, String> record =
                consumeOneWithKey("gmepay.journal.posted", journal.journalId());
        assertEquals("gmepay.journal.posted", record.topic());
        assertEquals(journal.journalId(), record.key(),
                "record key must be the aggregateId (journalId) for per-aggregate ordering");

        JsonNode payload = new ObjectMapper().readTree(record.value());
        assertEquals("journal.posted", payload.get("eventType").asText());
        assertEquals(journal.journalId(), payload.get("aggregateId").asText());
        assertNotNull(payload.get("occurredAt"), "payload must carry occurredAt");
        assertEquals(ours.getCreatedAt(), Instant.parse(payload.get("occurredAt").asText()),
                "occurredAt must be the outbox enqueue time (createdAt), not the publish time");

        // 4. Only now — after the synchronous, acked publish — is the row marked published.
        OutboxEntity reread = outboxRepository.findById(ours.getId()).orElseThrow();
        assertNotNull(reread.getPublishedAt(),
                "published_at must be stamped once the broker has acked the record");
    }

    /**
     * Polls {@code topic} from the earliest offset for up to 30s and returns the first record
     * whose key matches {@code expectedKey} (isolates this test's event from any other rows
     * the context may have drained).
     */
    private static ConsumerRecord<String, String> consumeOneWithKey(String topic, String expectedKey) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "gmepay-ledger-it-" + System.nanoTime());
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
