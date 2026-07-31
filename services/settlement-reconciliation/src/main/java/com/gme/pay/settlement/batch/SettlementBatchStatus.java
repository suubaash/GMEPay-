package com.gme.pay.settlement.batch;

import java.util.Map;
import java.util.Set;

/**
 * Lifecycle of an OUTBOUND settlement batch. Persisted as a VARCHAR on
 * {@code settlement_batches.status} (kept a String for H2/PG portability), with transitions enforced
 * in code via {@link #canMoveTo}.
 *
 * <pre>
 *   PENDING ──▶ GENERATED ──┬─▶ TRANSMITTED ──▶ RECEIVED ──▶ RECONCILED
 *      │            │       └──────────────────▶ RECEIVED
 *      └────────────┴──────────────┴──────────────┴──────────▶ ERROR ──▶ GENERATED (re-gen)
 * </pre>
 *
 * <h2>This enum answers ONE question — how far recon has got. It does NOT say whether we sent
 * anything (GAP T4-5)</h2>
 * The outbound spine drives PENDING → GENERATED only. RECEIVED/RECONCILED are driven by the inbound
 * recon path when a scheme confirmation file is genuinely parsed and tied out against the persisted
 * {@code settlement_lines}. Whether GME ever transmitted the <em>request</em> file is a separate
 * axis, on {@code settlement_batches.transmission_state}
 * ({@link com.gme.pay.settlement.transmission.SettlementTransmissionState}), because nothing in this
 * platform transmits: the only {@code SftpTransport} bean writes to a local temp directory.
 *
 * <p><b>Why GENERATED → RECEIVED is a legal edge.</b> {@code ReconDiffEngine} used to walk a batch
 * GENERATED → TRANSMITTED → RECEIVED, inventing a TRANSMITTED it had no evidence for purely to reach
 * a legal RECEIVED. That fast-forward is gone, and the direct edge exists so the recon path never
 * again needs to claim a transmission in order to record a reconciliation. TRANSMITTED remains in the
 * vocabulary but is now reachable <em>only</em> through
 * {@link com.gme.pay.settlement.transmission.SettlementTransmissionRecorder}, which refuses without a
 * configured channel — so it means what it says or it is not set at all.
 */
public enum SettlementBatchStatus {

    PENDING, GENERATED, TRANSMITTED, RECEIVED, RECONCILED, ERROR;

    private static final Map<SettlementBatchStatus, Set<SettlementBatchStatus>> ALLOWED = Map.of(
            PENDING,     Set.of(GENERATED, ERROR),
            // RECEIVED direct: a confirmation file can arrive for a batch we never transmitted
            // ourselves, and recording that must not require inventing a TRANSMITTED.
            GENERATED,   Set.of(TRANSMITTED, RECEIVED, ERROR),
            TRANSMITTED, Set.of(RECEIVED, ERROR),
            RECEIVED,    Set.of(RECONCILED, ERROR),
            RECONCILED,  Set.of(),
            ERROR,       Set.of(GENERATED));    // allow re-generation after a failed run

    public boolean canMoveTo(SettlementBatchStatus to) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(to);
    }
}
