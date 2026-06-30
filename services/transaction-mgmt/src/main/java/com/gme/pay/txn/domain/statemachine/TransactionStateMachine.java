package com.gme.pay.txn.domain.statemachine;

import com.gme.pay.events.DomainEvent;
import com.gme.pay.events.EventPublisher;
import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.outbox.OutboxAppender;
import com.gme.pay.txn.outbox.PaymentApprovedEvent;
import com.gme.pay.txn.outbox.TransactionCommittedEvent;
import com.gme.pay.txn.outbox.TransactionStatusChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
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

    private static final Logger log = LoggerFactory.getLogger(TransactionStateMachine.class);

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

        // Phase-2: stamp refundedAt the moment a txn enters REFUNDED, so the refund query
        // (GET /v1/transactions/refunded?refundedOn) can find it by refund date. The PATCH
        // contract carries no refund-date field, so this is the authoritative refund timestamp.
        // Best-effort: never let it block the transition.
        if (to == TransactionStatus.REFUNDED && txn.refundedAt() == null) {
            try {
                txn.applyRefundEnrichment(txn.refundAmountKrw(), txn.qrCodeId(),
                        Instant.now(), txn.originalPaymentTxnRef());
            } catch (RuntimeException ex) {
                log.warn("refund-enrichment stamp failed for txn {} (transition proceeds): {}",
                        txn.txnRef(), ex.toString());
            }
        }

        // Publish domain event to the outbox (same logical transaction in callers).
        DomainEvent event = new TransactionStatusChangedEvent(txn.txnRef(), from, to);
        eventPublisher.publish(event);

        // On APPROVED, also append the partner-facing payment.approved event so notification-webhook
        // can deliver a webhook. Appended after the status-changed event so outbox row ordering is
        // stable; goes to a different topic (gmepay.payment.approved) for a different consumer.
        //
        // Guard on partnerId: the webhook target is resolved by partner id, so an event with a null
        // partnerId (legacy 5-field transactions predating the V003 contract) would enqueue a
        // delivery row the dispatcher can never resolve — a permanently-stuck PENDING row. Such
        // transactions cannot notify a partner anyway, so we skip the event for them.
        if (to == TransactionStatus.APPROVED && txn.partnerId() != null) {
            eventPublisher.publish(new PaymentApprovedEvent(
                    txn.txnRef(), txn.partnerId(), txn.partnerTxnRef(), to));

            // Phase-2: at commit, capture the rate-locked FX projection (best-effort) and emit
            // transaction.committed so reporting-compliance/settlement/scheme-adapter can project
            // the committed cross-border txn without reading this DB. Wave-3: passing null margins
            // makes captureCommittedFxAtCommit fall back to the margins/collectionUsd ALREADY
            // persisted on the aggregate (the service applies the status-patch lock fields, incl.
            // the rate-lock pool, BEFORE this transition) → margin-accurate offerRateColl. When no
            // margins were carried (older rows) it collapses to the zero-margin approximation.
            // Wrapped so a projection/event failure NEVER fails the commit path.
            try {
                txn.captureCommittedFxAtCommit(null, null, Instant.now());
                eventPublisher.publish(TransactionCommittedEvent.from(txn));
            } catch (RuntimeException ex) {
                log.warn("committed-FX capture/publish failed for txn {} (commit proceeds): {}",
                        txn.txnRef(), ex.toString());
            }
        }

        return txn;
    }
}
