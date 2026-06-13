package com.gme.pay.txn.outbox;

import com.gme.pay.events.EventPublisher;
import com.gme.pay.events.kafka.KafkaEventPublisher;
import com.gme.pay.txn.domain.model.TransactionStatus;
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
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Failure-path companion to {@link OutboxKafkaPublishIT} (ticket 17.4-G02).
 *
 * <p>Activates the Kafka-backed {@link EventPublisher} (by setting
 * {@code spring.kafka.bootstrap-servers}) but points it at an unreachable host:port. The
 * {@link OutboxPublisher} drain therefore exercises the {@code EventPublishException}
 * branch:
 * <ul>
 *   <li>{@link KafkaEventPublisher#publish} blocks waiting for a broker ack and ultimately
 *       throws {@code EventPublishException} (timeout / unresolved host);</li>
 *   <li>{@link OutboxPublisher#publishPending()} catches it, logs at WARN, and leaves
 *       {@code published_at} {@code null} so the next tick retries — the at-least-once
 *       contract of the transactional Outbox.</li>
 * </ul>
 *
 * <p>Lives in a separate IT class (not a {@code @Nested} block) so it boots its own Spring
 * context with its own {@code spring.kafka.bootstrap-servers}: a {@code @DynamicPropertySource}
 * setting can only differ per ApplicationContext, and the happy-path IT needs the real
 * Testcontainers broker.
 *
 * <p>Only a Postgres container is started; no Kafka container is needed. The Kafka producer is
 * configured with a deliberately short {@code delivery.timeout.ms} / {@code max.block.ms} so
 * the test fails fast instead of waiting the publisher's default 10s timeout — the assertion
 * we care about is "row stayed unpublished", not "how long the broker failure took".
 *
 * <p>{@code @Tag("docker")}: excluded from the local {@code test} task; CI runs it via
 * {@code integrationTest}.
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        // The @Scheduled tick fires every hour so the explicit publishPending() call is the
        // only drain attempt this test observes.
        "gmepay.outbox.poll-ms=3600000",
        // Point at an unreachable broker. localhost:1 is reserved/unbound on any sane host;
        // combined with the short producer timeouts below this fails the send in seconds, not
        // the producer default 2 minutes.
        "spring.kafka.bootstrap-servers=localhost:1",
        "spring.kafka.producer.properties.max.block.ms=2000",
        "spring.kafka.producer.properties.delivery.timeout.ms=2000",
        "spring.kafka.producer.properties.request.timeout.ms=1000",
        "spring.kafka.producer.properties.reconnect.backoff.max.ms=500"
})
class OutboxKafkaUnreachableIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"));

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
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
    @DisplayName("EventPublishException on unreachable broker leaves outbox row unpublished for retry")
    void publishFailureLeavesRowUnpublished() {
        // The auto-configured Kafka publisher is the @Primary transport even though it points
        // at a dead host — that's exactly the scenario we want to fail safely under.
        assertInstanceOf(KafkaEventPublisher.class, eventPublisher,
                "spring.kafka.bootstrap-servers is set, so the auto-configured @Primary "
                        + "KafkaEventPublisher must be the active EventPublisher");

        // 1. Append an outbox row. OutboxRepository.save runs in its own Spring-Data tx and
        //    commits the row immediately. No broker call on this path.
        String txnRef = "TXN-OUTBOX-FAIL-IT-" + UUID.randomUUID();
        TransactionStatusChangedEvent event = new TransactionStatusChangedEvent(
                txnRef, TransactionStatus.CREATED, TransactionStatus.PENDING_DEBIT);
        outboxAppender.publish(event);

        OutboxEntity row = outboxRepository.findUnpublished(PageRequest.of(0, 1000)).stream()
                .filter(o -> txnRef.equals(o.getAggregateId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "expected an unpublished outbox row for txnRef " + txnRef));
        Long rowId = row.getId();
        assertNotNull(rowId, "row must be persisted with a generated id");
        assertNull(row.getPublishedAt(), "row must start unpublished");

        // 2. Drain. The broker is unreachable, so KafkaEventPublisher.publish blocks and
        //    eventually throws EventPublishException. OutboxPublisher MUST catch it and
        //    leave the row unpublished — never mark it published on failure.
        long start = System.nanoTime();
        outboxPublisher.publishPending();
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        // 3. Re-read: published_at is still null, so the next tick will retry. This is the
        //    at-least-once guarantee of the transactional Outbox.
        OutboxEntity reread = outboxRepository.findById(rowId).orElseThrow();
        assertNull(reread.getPublishedAt(),
                "publish failure (EventPublishException) must NOT mark the row published"
                        + " — at-least-once retry depends on it (took " + elapsed + ")");

        // And the lag count still includes our row.
        long lag = outboxRepository.findUnpublished(PageRequest.of(0, 1000)).stream()
                .filter(o -> txnRef.equals(o.getAggregateId()))
                .count();
        assertEquals(1L, lag, "row must still appear in findUnpublished for the next retry tick");
    }
}
