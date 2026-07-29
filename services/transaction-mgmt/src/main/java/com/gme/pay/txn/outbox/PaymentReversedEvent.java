package com.gme.pay.txn.outbox;

import com.gme.pay.contracts.events.PaymentReversedPayload;
import com.gme.pay.events.DomainEvent;
import com.gme.pay.txn.domain.model.Transaction;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Domain event appended to the outbox when a transaction's terminal outcome becomes
 * {@link com.gme.pay.txn.domain.model.TransactionStatus#REVERSED} — the money-terminal signal that
 * revenue-ledger books a reversing journal from and prefunding releases the held float on. Drains to
 * topic {@code gmepay.payment.reversed} (from {@link PaymentReversedPayload#EVENT_TYPE}).
 *
 * <p><b>Why this exists.</b> Before this, an operator force-resolve of an UNCERTAIN txn to REVERSED
 * emitted only the internal {@code TransactionStatusChanged} FSM event — no {@code payment.*} domain
 * event — so the reversal changed the financial outcome with ZERO ledger impact (defect #1). This
 * event carries the reversed amount + the held prefund USD ({@code reversedUsd}) so the release is
 * quantified on the same durable signal.
 *
 * <p>The record components mirror {@link PaymentReversedPayload} (camelCase; money as decimal STRINGs
 * per {@code docs/MONEY_CONVENTION.md}). {@code occurredAt} is carried as the {@link DomainEvent}
 * {@link Instant} (the canonical event mapper serializes it to the same ISO-8601 string the payload's
 * {@code occurredAt} String field expects), satisfying the interface without a duplicate component.
 * {@code aggregateId} = {@code txnRef} so per-subject ordering is preserved in the outbox drain.
 */
public record PaymentReversedEvent(
        String eventType,
        String txnRef,
        String partnerId,
        String schemeId,
        String reversedAmount,
        String currency,
        String reversedUsd,
        String reason,
        String source,
        Instant occurredAt
) implements DomainEvent {

    /** Reversal originated from an operator force-resolve (Ops force-resolve endpoint). */
    public static final String SOURCE_OPERATOR = "OPERATOR";

    /**
     * Reversal originated from an explicit REFUND of an already-captured payment (gap T2-6) — the
     * {@code POST /v1/payments/{id}/refund} and wallet-refund paths. Distinct from
     * {@link #SOURCE_OPERATOR} so a consumer (or an auditor) can tell a customer refund apart from an
     * operator force-resolve of a stuck transaction, even though both back the same money out.
     */
    public static final String SOURCE_REFUND = "REFUND";

    /**
     * Builds the reversal event from the txn snapshot at the moment it enters REVERSED.
     *
     * <ul>
     *   <li>{@code reversedAmount}/{@code currency} — the collection-leg amount + currency
     *       (falls back to the legacy send amount/ccy for pre-V003 rows).</li>
     *   <li>{@code reversedUsd} — the prefund USD held at UNCERTAIN ({@code prefundDeductedUsd}), so
     *       prefunding can release exactly what it held; null when absent.</li>
     *   <li>{@code reason} — the operator's resolution reason recorded on the aggregate.</li>
     *   <li>{@code source} — {@code OPERATOR}.</li>
     *   <li>{@code occurredAt} — now (ISO-8601 on the wire).</li>
     * </ul>
     */
    public static PaymentReversedEvent fromOperatorResolution(Transaction txn, Instant occurredAt) {
        Instant when = occurredAt != null ? occurredAt : Instant.now();
        BigDecimal amount = txn.collectionAmount() != null ? txn.collectionAmount() : txn.sendAmount();
        String currency = txn.collectionCurrency() != null ? txn.collectionCurrency() : txn.sendCcy();
        return new PaymentReversedEvent(
                PaymentReversedPayload.EVENT_TYPE,
                txn.txnRef(),
                txn.partnerId() != null ? String.valueOf(txn.partnerId()) : null,
                txn.schemeId(),
                amount != null ? amount.toPlainString() : null,
                currency,
                txn.prefundDeductedUsd() != null ? txn.prefundDeductedUsd().toPlainString() : null,
                txn.resolutionReason(),
                SOURCE_OPERATOR,
                when);
    }

    /**
     * Builds the reversal event for a transaction entering {@code REFUNDED} (gap T2-6).
     *
     * <p><b>Why this exists.</b> The state machine emitted {@code payment.reversed} only for
     * {@code REVERSED}. A {@code REFUNDED} transition — the money-terminal state of an explicit refund —
     * emitted nothing but the internal FSM status event, so revenue-ledger's reversal handler never ran for
     * a refund (captured FX margin / service charge / fee-share stayed on the books after the money went
     * back), prefunding never saw a release signal, and no partner-facing event existed to notify on.
     *
     * <p>Field mapping, all read off the aggregate so the event cannot disagree with the row:
     * <ul>
     *   <li>{@code reversedAmount}/{@code currency} — the <b>refunded</b> magnitude
     *       ({@code refundAmountKrw}, KRW) when one has been recorded, else the full collection amount +
     *       currency. A partial refund therefore carries the partial amount, not the original.</li>
     *   <li>{@code reversedUsd} — {@code prefundDeductedUsd} as committed by the refund patch, i.e. the USD
     *       actually credited back to the partner float for THIS refund at the original locked rate. This is
     *       the figure prefunding's {@code releaseReversedFloat} credits, so it must be the refunded USD and
     *       not the original deduction.</li>
     *   <li>{@code source} — {@link #SOURCE_REFUND}; {@code reason} — the resolution reason if one was
     *       recorded, else the literal {@code "REFUND"} so the field is never blank.</li>
     * </ul>
     */
    public static PaymentReversedEvent fromRefund(Transaction txn, Instant occurredAt) {
        Instant when = occurredAt != null ? occurredAt : Instant.now();
        BigDecimal amount = txn.refundAmountKrw();
        String currency = "KRW";
        if (amount == null) {
            amount = txn.collectionAmount() != null ? txn.collectionAmount() : txn.sendAmount();
            currency = txn.collectionCurrency() != null ? txn.collectionCurrency() : txn.sendCcy();
        }
        String reason = txn.resolutionReason() != null && !txn.resolutionReason().isBlank()
                ? txn.resolutionReason()
                : SOURCE_REFUND;
        return new PaymentReversedEvent(
                PaymentReversedPayload.EVENT_TYPE,
                txn.txnRef(),
                txn.partnerId() != null ? String.valueOf(txn.partnerId()) : null,
                txn.schemeId(),
                amount != null ? amount.toPlainString() : null,
                currency,
                txn.prefundDeductedUsd() != null ? txn.prefundDeductedUsd().toPlainString() : null,
                reason,
                SOURCE_REFUND,
                when);
    }

    /** Maps to the canonical wire payload (occurredAt rendered as ISO-8601). */
    public PaymentReversedPayload toPayload() {
        return new PaymentReversedPayload(
                eventType, txnRef, partnerId, schemeId, reversedAmount, currency,
                reversedUsd, reason, source, occurredAt != null ? occurredAt.toString() : null);
    }

    @Override
    public String aggregateId() {
        return txnRef;
    }
}
