package com.gme.pay.settlement.batch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Outbound settlement-batch lifecycle transitions. */
class SettlementBatchStatusTest {

    @Test
    @DisplayName("allowed transitions for the outbound + recon legs")
    void allowed() {
        assertTrue(SettlementBatchStatus.PENDING.canMoveTo(SettlementBatchStatus.GENERATED));
        assertTrue(SettlementBatchStatus.PENDING.canMoveTo(SettlementBatchStatus.ERROR));
        assertTrue(SettlementBatchStatus.GENERATED.canMoveTo(SettlementBatchStatus.TRANSMITTED));
        assertTrue(SettlementBatchStatus.RECEIVED.canMoveTo(SettlementBatchStatus.RECONCILED));
        assertTrue(SettlementBatchStatus.ERROR.canMoveTo(SettlementBatchStatus.GENERATED)); // re-generate
    }

    @Test
    @DisplayName("disallowed transitions are rejected")
    void disallowed() {
        assertFalse(SettlementBatchStatus.PENDING.canMoveTo(SettlementBatchStatus.RECONCILED));
        assertFalse(SettlementBatchStatus.GENERATED.canMoveTo(SettlementBatchStatus.RECONCILED));
        assertFalse(SettlementBatchStatus.RECONCILED.canMoveTo(SettlementBatchStatus.GENERATED));
        assertFalse(SettlementBatchStatus.GENERATED.canMoveTo(SettlementBatchStatus.PENDING));
    }

    /**
     * GAP T4-5: the recon path must be able to record a reconciliation WITHOUT first claiming a
     * transmission. Before this edge existed, {@code ReconDiffEngine} walked
     * GENERATED → TRANSMITTED → RECEIVED purely to reach a legal RECEIVED, which left every reconciled
     * batch reading as though GME had sent the request file. Nothing ever had.
     */
    @Test
    @DisplayName("T4-5: GENERATED -> RECEIVED directly, so recon never has to invent a TRANSMITTED")
    void generatedGoesStraightToReceived() {
        assertTrue(SettlementBatchStatus.GENERATED.canMoveTo(SettlementBatchStatus.RECEIVED));
    }
}
