package com.gme.pay.settlement.batch;

import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of an OUTBOUND settlement batch. Persisted as a VARCHAR on
 * {@code settlement_batches.status} (kept a String for H2/PG portability), with transitions enforced
 * in code via {@link #canMoveTo}.
 *
 * <pre>
 *   PENDING ──▶ GENERATED ──▶ TRANSMITTED ──▶ RECEIVED ──▶ RECONCILED
 *      │            │              │              │
 *      └────────────┴──────────────┴──────────────┴──────────▶ ERROR ──▶ GENERATED (re-gen)
 * </pre>
 *
 * The outbound spine drives PENDING → GENERATED only; TRANSMITTED/RECEIVED are the (out-of-scope)
 * SFTP legs, and RECONCILED is set by the existing inbound recon path.
 */
public enum SettlementBatchStatus {

    PENDING, GENERATED, TRANSMITTED, RECEIVED, RECONCILED, ERROR;

    private static final Map<SettlementBatchStatus, Set<SettlementBatchStatus>> ALLOWED = Map.of(
            PENDING,     Set.of(GENERATED, ERROR),
            GENERATED,   Set.of(TRANSMITTED, ERROR),
            TRANSMITTED, Set.of(RECEIVED, ERROR),
            RECEIVED,    Set.of(RECONCILED, ERROR),
            RECONCILED,  Set.of(),
            ERROR,       Set.of(GENERATED));    // allow re-generation after a failed run

    public boolean canMoveTo(SettlementBatchStatus to) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(to);
    }
}
