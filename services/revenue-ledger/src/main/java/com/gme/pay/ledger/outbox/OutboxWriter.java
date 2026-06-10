package com.gme.pay.ledger.outbox;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * Inserts {@link OutboxEntity} rows for domain events that need to be published asynchronously.
 *
 * <p><strong>Transactional contract:</strong> this component intentionally has NO
 * {@code @Transactional} annotation. It is meant to be called from within an existing
 * transaction (e.g. inside {@code JpaJournalStore.save(Journal)}) so the outbox INSERT
 * is committed atomically with the business write. If the business work rolls back,
 * the outbox row rolls back with it — that is the whole point of the Outbox pattern.
 *
 * <p>Calling {@code enqueue} outside any transaction will still work (Spring Data will
 * open a per-statement transaction), but the at-least-once guarantee only holds when
 * the caller's transaction wraps both writes.
 */
@Component
public class OutboxWriter {

    private final OutboxRepository repository;

    public OutboxWriter(OutboxRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository required");
    }

    /**
     * Enqueue a domain event for asynchronous publishing.
     *
     * @param aggregateId business id of the aggregate that produced the event (e.g. journalId)
     * @param eventType   event type discriminator (e.g. {@code "journal.posted"})
     * @param payload     opaque event body (typically JSON); consumers parse based on {@code eventType}
     */
    public void enqueue(String aggregateId, String eventType, String payload) {
        Objects.requireNonNull(aggregateId, "aggregateId required");
        Objects.requireNonNull(eventType, "eventType required");
        Objects.requireNonNull(payload, "payload required");
        repository.save(new OutboxEntity(aggregateId, eventType, payload, Instant.now()));
    }
}
