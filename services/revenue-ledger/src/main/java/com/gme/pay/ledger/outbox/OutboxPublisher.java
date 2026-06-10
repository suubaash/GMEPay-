package com.gme.pay.ledger.outbox;

import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Scheduled drain for the transactional Outbox.
 *
 * <p>On every tick (default 1 s, configurable via {@code gmepay.outbox.poll-ms}):
 * <ol>
 *   <li>read the oldest unpublished rows (up to {@link #BATCH_SIZE});</li>
 *   <li>for each row, build a {@link DomainEvent} and hand it to {@link EventPublisher};</li>
 *   <li>on success, stamp {@code publishedAt} so the row is not picked up again.</li>
 * </ol>
 *
 * <p><strong>Failure handling.</strong> Per-row {@code publish(...)} exceptions are caught
 * and logged at WARN; {@code publishedAt} stays null so the next tick retries. This is the
 * at-least-once half of the contract — consumers must be idempotent.
 *
 * <p>The whole tick runs in a single transaction so the {@code SELECT} and {@code UPDATE}
 * see a consistent view; an exception thrown OUT of {@link #publishPending()} (e.g. from
 * the repository) rolls the tick back and Spring's scheduler retries on the next interval.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    /** Maximum rows drained per tick — keeps a backlog from blowing out one transaction. */
    static final int BATCH_SIZE = 100;

    private final OutboxRepository repository;
    private final EventPublisher eventPublisher;

    public OutboxPublisher(OutboxRepository repository, EventPublisher eventPublisher) {
        this.repository = Objects.requireNonNull(repository, "repository required");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher required");
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
                eventPublisher.publish(toDomainEvent(row));
                row.setPublishedAt(Instant.now());
                repository.save(row);
            } catch (RuntimeException ex) {
                // Leave publishedAt = null so the next tick retries. Do not let one bad row
                // poison the rest of the batch.
                log.warn("outbox publish failed for id={} eventType={} aggregateId={}: {}",
                        row.getId(), row.getEventType(), row.getAggregateId(), ex.toString());
            }
        }
    }

    /**
     * Adapt a persisted {@link OutboxEntity} to the in-process {@link DomainEvent} contract
     * consumed by {@link EventPublisher}. {@code occurredAt} is the row's {@code createdAt}
     * so re-publishes preserve the original event time (not the publish time).
     */
    private static DomainEvent toDomainEvent(OutboxEntity row) {
        final String eventType = row.getEventType();
        final String aggregateId = row.getAggregateId();
        final Instant occurredAt = row.getCreatedAt();
        return new DomainEvent() {
            @Override public String eventType()   { return eventType; }
            @Override public String aggregateId() { return aggregateId; }
            @Override public Instant occurredAt() { return occurredAt; }
        };
    }
}
