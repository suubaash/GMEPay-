package com.gme.pay.txn.outbox;

import com.gme.pay.events.DomainEvent;
import com.gme.pay.txn.domain.model.TransactionStatus;

import java.time.Instant;

/**
 * Partner-facing domain event emitted when a transaction reaches {@link TransactionStatus#APPROVED}.
 *
 * <p>Published (via the outbox) to topic {@code gmepay.payment.approved}, which the
 * notification-webhook service consumes to deliver a partner webhook. Carries {@code partnerId}
 * because the webhook target is resolved by partner id (config-registry registers the endpoint at
 * activation); {@code partnerTxnRef} lets the partner correlate the notification to their own ref.
 *
 * <p>Distinct from {@link TransactionStatusChangedEvent}: that internal event fires on every
 * transition (topic {@code gmepay.TransactionStatusChanged}); this one fires only on APPROVED and
 * exists purely to drive the external webhook contract.
 */
public record PaymentApprovedEvent(
        String txnRef,
        Long partnerId,
        String partnerTxnRef,
        TransactionStatus toStatus,
        Instant occurredAt
) implements DomainEvent {

    public PaymentApprovedEvent(String txnRef, Long partnerId, String partnerTxnRef,
                                TransactionStatus toStatus) {
        this(txnRef, partnerId, partnerTxnRef, toStatus, Instant.now());
    }

    /** Drives the topic name {@code gmepay.payment.approved} and the consumer's eventType gate. */
    @Override
    public String eventType() {
        return "payment.approved";
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
