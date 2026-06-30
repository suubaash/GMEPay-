package com.gme.pay.payment.domain.event;

import com.gme.pay.events.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The domain events payment-executor EXPOSES on the money flow (service contract: events
 * {@code payment.approved}, {@code payment.failed}; plus {@code payment.cancelled} for the same-day
 * void path — backlog 5.2-T13 / 5.6-T11).
 *
 * <p>Each is a {@link DomainEvent} record so the {@code aggregateId} is the {@code payment_id} and
 * the money fields ride alongside the contract fields when serialized. They are published through the
 * service's {@link com.gme.pay.events.EventPublisher} bean — a no-infra {@link
 * com.gme.pay.events.LogEventPublisher} here, superseded by an outbox→Kafka publisher at integration
 * without any caller change.
 */
public final class PaymentEvents {

    private PaymentEvents() {
    }

    /** Emitted after a confirm captures and the scheme APPROVES (terminal success). */
    public record PaymentApproved(
            String aggregateId,
            Instant occurredAt,
            long partnerId,
            String partnerTxnRef,
            String schemeTxnId,
            String merchantId,
            BigDecimal targetPayout,
            String payoutCurrency,
            BigDecimal collectionAmount,
            String collectionCurrency) implements DomainEvent {

        @Override
        public String eventType() {
            return "payment.approved";
        }
    }

    /** Emitted when the scheme DECLINES at confirm (terminal failure; hold already released). */
    public record PaymentFailed(
            String aggregateId,
            Instant occurredAt,
            long partnerId,
            String partnerTxnRef,
            String reason) implements DomainEvent {

        @Override
        public String eventType() {
            return "payment.failed";
        }
    }

    /** Emitted after a successful same-day cancellation (prefund reversed for OVERSEAS). */
    public record PaymentCancelled(
            String aggregateId,
            Instant occurredAt,
            long partnerId,
            String reason,
            BigDecimal prefundReturnedUsd) implements DomainEvent {

        @Override
        public String eventType() {
            return "payment.cancelled";
        }
    }
}
