package com.gme.pay.txn.outbox;

import com.gme.pay.events.DomainEvent;
import com.gme.pay.txn.domain.model.TransactionStatus;

import java.time.Instant;

/**
 * Domain event appended to the outbox whenever a transaction's status changes.
 *
 * <p>Implements {@link DomainEvent} (from {@code lib-events}) so it can be published
 * via the {@link com.gme.pay.events.EventPublisher} interface.
 */
public record TransactionStatusChangedEvent(
        String txnRef,
        TransactionStatus fromStatus,
        TransactionStatus toStatus,
        Instant occurredAt
) implements DomainEvent {

    public TransactionStatusChangedEvent(
            String txnRef, TransactionStatus fromStatus, TransactionStatus toStatus) {
        this(txnRef, fromStatus, toStatus, Instant.now());
    }

    @Override
    public String eventType() {
        return "TransactionStatusChanged";
    }

    @Override
    public String aggregateId() {
        return txnRef;
    }

    @Override
    public Instant occurredAt() {
        return occurredAt;
    }
}
