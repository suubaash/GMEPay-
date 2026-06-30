package com.gme.pay.txn.outbox;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.gme.pay.contracts.events.TransactionCommittedPayload;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.txn.domain.model.Transaction;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Domain event appended to the outbox when a transaction commits (reaches APPROVED), carrying the
 * rate-locked FX fields the canonical {@code GET /v1/transactions} omits. Drains to topic
 * {@code gmepay.transaction.committed} (from {@link TransactionCommittedPayload#EVENT_TYPE}).
 *
 * <p>Consumed by reporting-compliance (BOK FX1015 #14), settlement-reconciliation (netting),
 * scheme-adapter (refund/fee/value-date enrichment) and revenue-ledger. This is the event sibling
 * of the {@link com.gme.pay.contracts.CommittedFxView} query projection — the record components
 * mirror {@link TransactionCommittedPayload} so the canonical event serializer emits the exact
 * camelCase, decimal-string contract wire shape.
 *
 * <p>{@code eventType}/{@code aggregateId}/{@code occurredAt} satisfy {@link DomainEvent}; the
 * remaining components are the payload carried on the wire.
 */
public record TransactionCommittedEvent(
        String aggregateId,
        String txnRef,
        Instant occurredAt,
        long partnerId,
        String direction,
        boolean sameCcyShortcircuit,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal offerRateColl,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal crossRate,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal collectionAmount,
        String collectionCcy,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal payoutAmount,
        String payoutCcy,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal usdAmount,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal collectionMarginUsd,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal payoutMarginUsd,
        Instant committedAt
) implements DomainEvent {

    /** Builds the event from a committed aggregate (call after the FX capture). */
    public static TransactionCommittedEvent from(Transaction txn) {
        Instant committed = txn.committedAt() != null ? txn.committedAt() : Instant.now();
        return new TransactionCommittedEvent(
                txn.txnRef(),
                txn.txnRef(),
                committed,
                txn.partnerId() != null ? txn.partnerId() : 0L,
                txn.direction(),
                Boolean.TRUE.equals(txn.sameCcyShortcircuit()),
                txn.offerRateColl(),
                txn.crossRate(),
                txn.collectionAmount() != null ? txn.collectionAmount() : txn.sendAmount(),
                txn.collectionCurrency() != null ? txn.collectionCurrency() : txn.sendCcy(),
                txn.targetPayout(),
                txn.payoutCurrency() != null ? txn.payoutCurrency() : txn.targetCcy(),
                txn.usdAmount(),
                txn.collectionMarginUsd(),
                txn.payoutMarginUsd(),
                committed);
    }

    /** {@code transaction.committed} → topic {@code gmepay.transaction.committed}. */
    @Override
    public String eventType() {
        return TransactionCommittedPayload.EVENT_TYPE;
    }
}
