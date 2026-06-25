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
}
