package com.gme.pay.txn.domain.statemachine;

import com.gme.pay.txn.domain.model.TransactionStatus;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Authoritative, immutable table of permitted state-to-state transitions for a GMEPay+
 * transaction (wave scope: CREATED → PENDING_DEBIT → APPROVED / FAILED / CANCELLED).
 *
 * <p>All transitions not listed here are forbidden.  Terminal states map to an empty set.
 */
public final class TransactionTransitions {

    /** Permitted transitions: from → {allowed to-states}. */
    private static final Map<TransactionStatus, Set<TransactionStatus>> ALLOWED;

    static {
        Map<TransactionStatus, Set<TransactionStatus>> m = new EnumMap<>(TransactionStatus.class);

        // CREATED: start of life
        m.put(TransactionStatus.CREATED, EnumSet.of(
                TransactionStatus.PENDING_DEBIT,   // OVERSEAS: begin prefund deduction
                TransactionStatus.APPROVED,         // LOCAL (no prefund): direct approval
                TransactionStatus.FAILED,           // TTL expiry or validation failure
                TransactionStatus.CANCELLED         // cancelled before debit
        ));

        // PENDING_DEBIT: prefund deduction in flight
        m.put(TransactionStatus.PENDING_DEBIT, EnumSet.of(
                TransactionStatus.APPROVED,         // deduction succeeded, scheme approved
                TransactionStatus.FAILED,           // insufficient prefunding or scheme reject
                TransactionStatus.CANCELLED         // cancelled while debit in progress
        ));

        // APPROVED: a same-day cancel reverses it; an explicit refund refunds it.
        m.put(TransactionStatus.APPROVED, EnumSet.of(
                TransactionStatus.REVERSED,         // same-day cancel (prefunding reversed)
                TransactionStatus.REFUNDED          // explicit post-approval refund
        ));

        // Terminal states have no outgoing transitions
        m.put(TransactionStatus.FAILED,     Collections.emptySet());
        m.put(TransactionStatus.CANCELLED,  Collections.emptySet());
        m.put(TransactionStatus.REVERSED,   Collections.emptySet());
        m.put(TransactionStatus.REFUNDED,   Collections.emptySet());

        ALLOWED = Collections.unmodifiableMap(m);
    }

    private TransactionTransitions() {}

    /**
     * Returns {@code true} if transitioning {@code from} → {@code to} is a legal move.
     */
    public static boolean isAllowed(TransactionStatus from, TransactionStatus to) {
        Set<TransactionStatus> targets = ALLOWED.get(from);
        return targets != null && targets.contains(to);
    }

    /**
     * Returns the (possibly empty) set of states reachable from {@code from}.
     */
    public static Set<TransactionStatus> allowedFrom(TransactionStatus from) {
        return ALLOWED.getOrDefault(from, Collections.emptySet());
    }

    /** Returns the full transition map (unmodifiable, useful for diagnostics). */
    public static Map<TransactionStatus, Set<TransactionStatus>> allTransitions() {
        return ALLOWED;
    }
}
