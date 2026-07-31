package com.gme.pay.settlement.corridor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Leg (b) of the cross-border three-way tie-out: <b>our own prefunding movement</b> — the USD the
 * hub moved on the partner's float for one payment, as prefunding reports it.
 *
 * <p><b>Sign convention (T2-8).</b> {@code amountUsd} is the <em>float consumed</em> by this entry:
 * <b>positive for a deduction, negative for a credit back</b> (a reversal). The reconciler sums
 * movements per reference, so a deduct and its reversal net to zero all by themselves — which is the
 * whole point of reading prefunding's date-ranged movement query instead of its deductions-only
 * history. It is the negation of prefunding's {@code balanceDeltaUsd}: that field is signed from the
 * balance's point of view, this one from the float-consumed point of view, so it compares directly
 * against a transaction's {@code prefundingDeductedUsd}.
 *
 * @param reference the reference the movement was keyed on — the hub partner reference, so it joins
 *                  directly to {@link SchemeTransactionRecord#joinKey()}
 * @param amountUsd USD of float consumed: positive = deducted, negative = credited back
 * @param at        instant of the movement
 * @param entryType prefunding's raw ledger entry type ({@code DEBIT} / {@code CREDIT} /
 *                  {@code CAPTURE} / …) — carried for diagnostics, never for arithmetic; the sign
 *                  already encodes the direction
 */
public record PrefundingMovement(String reference, BigDecimal amountUsd, Instant at,
                                 String entryType) {

    /** Entry type assumed when a caller does not state one: a plain deduction. */
    public static final String DEFAULT_ENTRY_TYPE = "DEBIT";

    /** Convenience for the common case — a deduction. Keeps existing call sites source-compatible. */
    public PrefundingMovement(String reference, BigDecimal amountUsd, Instant at) {
        this(reference, amountUsd, at, DEFAULT_ENTRY_TYPE);
    }
}
