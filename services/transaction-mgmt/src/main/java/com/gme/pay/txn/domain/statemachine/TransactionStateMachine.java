package com.gme.pay.txn.domain.statemachine;

import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.outbox.OutboxAppender;
import com.gme.pay.txn.outbox.TransactionStatusChangedEvent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * The only component that may mutate a {@link Transaction}'s status.
 *
 * <p>Responsibilities:
 * <ol>
 *   <li>Guard: verify the transition is allowed via {@link TransactionTransitions}.</li>
 *   <li>Mutate: apply the new status to the aggregate.</li>
 *   <li>Publish: append a {@link TransactionStatusChangedEvent} to the outbox so downstream
 *       consumers are notified asynchronously (Phase 1: in-memory / DB outbox).</li>
 * </ol>
 *
 * <p>This class is intentionally {@code @Component}-only (no {@code @Transactional}) so
 * callers decide the transaction boundary.
 */
@Component
public class TransactionStateMachine {

    private final EventPublisher eventPublisher;

    /**
     * The injected publisher is qualified to the outbox-appending bean
     * ({@link OutboxAppender#BEAN_NAME}) so status-change events are written to the
     * {@code outbox} table inside the caller's DB transaction — never sent straight to
     * Kafka from inside a business transaction (lib-events-kafka registers its publisher
     * {@code @Primary}, which would otherwise win the by-type injection).
     */
    public TransactionStateMachine(@Qualifier(OutboxAppender.BEAN_NAME) EventPublisher eventPublisher) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
    }

    /**
     * Attempts to move {@code txn} from its current status to {@code to}.
     *
     * @param txn the aggregate to transition
     * @param to  the desired target status
     * @return the same {@code txn} instance, with status updated
     * @throws TransitionBlockedException if the transition is not in the permitted table
     */
    public Transaction transition(Transaction txn, TransactionStatus to) {
        Objects.requireNonNull(txn, "txn");
        Objects.requireNonNull(to, "to");

        TransactionStatus from = txn.status();
        if (!TransactionTransitions.isAllowed(from, to)) {
            throw new TransitionBlockedException(txn.txnRef(), from, to);
        }

        txn.applyStatus(to);

        // Publish domain event to the outbox (same logical transaction in callers).
        DomainEvent event = new TransactionStatusChangedEvent(txn.txnRef(), from, to);
        eventPublisher.publish(event);

        return txn;
    }
}
