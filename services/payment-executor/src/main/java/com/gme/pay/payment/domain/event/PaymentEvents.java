package com.gme.pay.payment.domain.event;

import com.gme.pay.contracts.events.PaymentApprovedPayload;
import com.gme.pay.events.DomainEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

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

    /**
     * Emitted after a confirm captures and the scheme APPROVES (terminal success).
     *
     * <p>This is the revenue-bearing event consumed by revenue-ledger (capture) and
     * notification-webhook (delivery). Its canonical wire form is {@link PaymentApprovedPayload}
     * (lib-api-contracts, camelCase, money as decimal strings); {@link #payload()} maps this domain
     * record onto it so the {@code EventPublisher} seam serializes the canonical contract — a no-infra
     * {@link com.gme.pay.events.LogEventPublisher} here, an outbox→Kafka publisher at integration.
     *
     * <p>The revenue fields ({@code collectionMarginUsd}, {@code payoutMarginUsd},
     * {@code serviceChargeAmount}/{@code serviceChargeCcy}, {@code feeSharePct}) are snapshotted at
     * authorize on the {@code PaymentAuthorizationEntity} and replayed here at confirm.
     */
    public record PaymentApproved(
            String aggregateId,
            Instant occurredAt,
            LocalDate revenueDate,
            long partnerId,
            long schemeId,
            String partnerTxnRef,
            String txnRef,
            BigDecimal collectionMarginUsd,
            BigDecimal payoutMarginUsd,
            BigDecimal serviceChargeAmount,
            String serviceChargeCcy,
            BigDecimal feeSharePct) implements DomainEvent {

        @Override
        public String eventType() {
            return PaymentApprovedPayload.EVENT_TYPE;
        }

        /** The canonical lib-api-contracts payload that rides the wire (topic {@code gmepay.payment.approved}). */
        public PaymentApprovedPayload payload() {
            return new PaymentApprovedPayload(
                    PaymentApprovedPayload.EVENT_TYPE, aggregateId, txnRef, occurredAt, revenueDate,
                    partnerId, schemeId, collectionMarginUsd, payoutMarginUsd,
                    serviceChargeAmount, serviceChargeCcy, feeSharePct);
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
