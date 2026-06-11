package com.gme.pay.txn.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Scheduled drain for the transactional Outbox (ticket 17.4-G02).
 *
 * <p>On every tick (default 1 s, configurable via {@code gmepay.outbox.poll-ms}):
 * <ol>
 *   <li>read the oldest unpublished rows (up to {@link #BATCH_SIZE}, {@code id} ascending so
 *       per-aggregate ordering is preserved);</li>
 *   <li>for each row, rebuild a {@link DomainEvent} (contract fields from the columns, the
 *       stored payload re-attached under {@code payload}) and hand it to the transport
 *       {@link EventPublisher};</li>
 *   <li><strong>only after {@code publish(...)} returns successfully</strong>, stamp
 *       {@code publishedAt} so the row is not picked up again.</li>
 * </ol>
 *
 * <p><strong>Failure handling.</strong> Per-row publish exceptions (e.g.
 * {@code EventPublishException} from the Kafka transport on broker error / timeout) are
 * caught and logged at WARN; {@code publishedAt} stays {@code null} so the next tick
 * retries. A crash between broker ack and the DB update re-publishes the row — this is the
 * at-least-once half of the contract; consumers must be idempotent. Rows are never marked
 * published on failure.
 *
 * <p><strong>Lag metric.</strong> {@link #pendingLag()} exposes the count of unpublished
 * rows; each tick that ends with a backlog logs it at INFO so operators can alert on it.
 */
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    /** Maximum rows drained per tick — keeps a backlog from blowing out one transaction. */
    static final int BATCH_SIZE = 100;

    private static final ObjectMapper PAYLOAD_READER = new ObjectMapper();

    private final OutboxRepository repository;
    private final EventPublisher transport;

    public OutboxPublisher(OutboxRepository repository, EventPublisher transport) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Scheduled(fixedDelayString = "${gmepay.outbox.poll-ms:1000}")
    @Transactional
    public void publishPending() {
        List<OutboxEntity> batch = repository.findUnpublished(PageRequest.of(0, BATCH_SIZE));
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxEntity row : batch) {
            try {
                transport.publish(toDomainEvent(row));
                // Reached ONLY when the transport acked (synchronous send) — a failed
                // publish throws and the row keeps publishedAt = null for retry.
                row.setPublishedAt(Instant.now());
                repository.save(row);
            } catch (RuntimeException ex) {
                // Leave publishedAt = null so the next tick retries. Do not let one bad
                // row poison the rest of the batch.
                log.warn("outbox publish failed for id={} eventType={} aggregateId={}: {}",
                        row.getId(), row.getEventType(), row.getAggregateId(), ex.toString());
            }
        }
        long lag = repository.countByPublishedAtIsNull();
        if (lag > 0) {
            log.info("outbox lag: {} unpublished row(s) pending retry", lag);
        }
    }

    /** Outbox lag — rows committed to the outbox but not yet acked by the transport. */
    public long pendingLag() {
        return repository.countByPublishedAtIsNull();
    }

    /**
     * Adapts a persisted row back to the {@link DomainEvent} contract. {@code occurredAt} is
     * the row's {@code createdAt} so re-publishes preserve the original event time, and the
     * stored payload travels as a {@code payload} bean property so the Kafka serializer
     * emits it as a nested JSON object (raw string fallback if the column is not valid JSON).
     */
    static DomainEvent toDomainEvent(OutboxEntity row) {
        return new OutboxBackedEvent(
                row.getEventType(), row.getAggregateId(), row.getCreatedAt(),
                parsePayload(row.getPayload()));
    }

    private static JsonNode parsePayload(String payload) {
        if (payload == null) {
            return TextNode.valueOf("");
        }
        try {
            return PAYLOAD_READER.readTree(payload);
        } catch (Exception e) {
            return TextNode.valueOf(payload);
        }
    }

    /**
     * Record components deliberately mirror the {@link DomainEvent} accessors so the
     * canonical event serializer writes {@code eventType}/{@code aggregateId}/{@code occurredAt}
     * once, plus the preserved {@code payload}.
     */
    record OutboxBackedEvent(String eventType, String aggregateId, Instant occurredAt,
                             JsonNode payload) implements DomainEvent {
    }
}
