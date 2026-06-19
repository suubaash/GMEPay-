package com.gme.pay.settlement.outbox;

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
 * Scheduled drain for the settlement transactional Outbox. Each tick (default 1 s,
 * {@code gmepay.outbox.poll-ms}) reads the oldest unpublished rows, rebuilds a {@link DomainEvent}
 * (contract fields from the columns, stored payload re-attached under {@code payload}), hands it to the
 * transport {@link EventPublisher}, and stamps {@code publishedAt} ONLY after a successful publish.
 * Per-row failures are logged and left for the next tick (at-least-once; consumers must be idempotent).
 * Ported verbatim from transaction-mgmt.
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
                row.setPublishedAt(Instant.now());
                repository.save(row);
            } catch (RuntimeException ex) {
                log.warn("outbox publish failed for id={} eventType={} aggregateId={}: {}",
                        row.getId(), row.getEventType(), row.getAggregateId(), ex.toString());
            }
        }
        long lag = repository.countByPublishedAtIsNull();
        if (lag > 0) {
            log.info("settlement outbox lag: {} unpublished row(s) pending retry", lag);
        }
    }

    /** Outbox lag — rows committed but not yet acked by the transport. */
    public long pendingLag() {
        return repository.countByPublishedAtIsNull();
    }

    /** Adapts a persisted row back to the {@link DomainEvent} contract (payload re-attached as JSON). */
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

    record OutboxBackedEvent(String eventType, String aggregateId, Instant occurredAt,
                             JsonNode payload) implements DomainEvent {
    }
}
