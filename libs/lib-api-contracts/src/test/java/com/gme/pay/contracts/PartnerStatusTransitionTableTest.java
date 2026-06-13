package com.gme.pay.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the Slice 8 partner lifecycle FSM ({@link PartnerStatusTransitionTable})
 * exactly as ADR-011 documents it: the twelve legal edges, the 4-eyes flags on
 * the LIVE-adjacent edges, and the rejection of everything else (state skips,
 * backward moves, resurrection from TERMINATED).
 */
class PartnerStatusTransitionTableTest {

    @Test
    @DisplayName("every forward-flow wizard edge is allowed")
    void forwardFlowEdgesAllowed() {
        assertTrue(PartnerStatusTransitionTable.isAllowed(
                PartnerStatus.DRAFT, PartnerStatus.ONBOARDING));
        assertTrue(PartnerStatusTransitionTable.isAllowed(
                PartnerStatus.ONBOARDING, PartnerStatus.KYB_PENDING));
        assertTrue(PartnerStatusTransitionTable.isAllowed(
                PartnerStatus.KYB_PENDING, PartnerStatus.KYB_APPROVED));
        assertTrue(PartnerStatusTransitionTable.isAllowed(
                PartnerStatus.KYB_APPROVED, PartnerStatus.CONTRACT_SIGNED));
        assertTrue(PartnerStatusTransitionTable.isAllowed(
                PartnerStatus.CONTRACT_SIGNED, PartnerStatus.SANDBOX));
        assertTrue(PartnerStatusTransitionTable.isAllowed(
                PartnerStatus.SANDBOX, PartnerStatus.UAT));
        assertTrue(PartnerStatusTransitionTable.isAllowed(
                PartnerStatus.UAT, PartnerStatus.LIVE));
    }

    @Test
    @DisplayName("KYB rework: KYB_PENDING may fall back to ONBOARDING")
    void kybReworkEdgeAllowed() {
        assertTrue(PartnerStatusTransitionTable.isAllowed(
                PartnerStatus.KYB_PENDING, PartnerStatus.ONBOARDING));
        // ...but no other backward edge exists.
        assertFalse(PartnerStatusTransitionTable.isAllowed(
                PartnerStatus.KYB_APPROVED, PartnerStatus.ONBOARDING));
        assertFalse(PartnerStatusTransitionTable.isAllowed(
                PartnerStatus.UAT, PartnerStatus.SANDBOX));
    }

    @Test
    @DisplayName("LIVE-adjacent edges require 4-eyes; wizard edges do not")
    void fourEyesFlags() {
        assertTrue(PartnerStatusTransitionTable.requiresFourEyes(
                PartnerStatus.UAT, PartnerStatus.LIVE));
        assertTrue(PartnerStatusTransitionTable.requiresFourEyes(
                PartnerStatus.LIVE, PartnerStatus.SUSPENDED));
        assertTrue(PartnerStatusTransitionTable.requiresFourEyes(
                PartnerStatus.SUSPENDED, PartnerStatus.LIVE));
        assertTrue(PartnerStatusTransitionTable.requiresFourEyes(
                PartnerStatus.LIVE, PartnerStatus.TERMINATED));
        assertTrue(PartnerStatusTransitionTable.requiresFourEyes(
                PartnerStatus.SUSPENDED, PartnerStatus.TERMINATED));

        assertFalse(PartnerStatusTransitionTable.requiresFourEyes(
                PartnerStatus.DRAFT, PartnerStatus.ONBOARDING));
        assertFalse(PartnerStatusTransitionTable.requiresFourEyes(
                PartnerStatus.ONBOARDING, PartnerStatus.KYB_PENDING));
        assertFalse(PartnerStatusTransitionTable.requiresFourEyes(
                PartnerStatus.SANDBOX, PartnerStatus.UAT));
    }

    @Test
    @DisplayName("state skips and illegal jumps are rejected")
    void forbiddenTransitionsRejected() {
        assertFalse(PartnerStatusTransitionTable.isAllowed(
                PartnerStatus.DRAFT, PartnerStatus.LIVE));
        assertFalse(PartnerStatusTransitionTable.isAllowed(
                PartnerStatus.ONBOARDING, PartnerStatus.KYB_APPROVED));
        assertFalse(PartnerStatusTransitionTable.isAllowed(
                PartnerStatus.SANDBOX, PartnerStatus.LIVE));
        assertFalse(PartnerStatusTransitionTable.isAllowed(
                PartnerStatus.UAT, PartnerStatus.SUSPENDED));
        assertFalse(PartnerStatusTransitionTable.isAllowed(
                PartnerStatus.LIVE, PartnerStatus.UAT));
        assertFalse(PartnerStatusTransitionTable.isAllowed(
                PartnerStatus.ONBOARDING, PartnerStatus.TERMINATED));
        // Self-loops are not edges either.
        assertFalse(PartnerStatusTransitionTable.isAllowed(
                PartnerStatus.LIVE, PartnerStatus.LIVE));
    }

    @Test
    @DisplayName("TERMINATED is terminal — no outbound edges")
    void terminatedIsTerminal() {
        assertTrue(PartnerStatusTransitionTable.targetsFrom(PartnerStatus.TERMINATED).isEmpty());
        for (PartnerStatus to : PartnerStatus.values()) {
            assertFalse(PartnerStatusTransitionTable.isAllowed(PartnerStatus.TERMINATED, to),
                    "TERMINATED -> " + to + " must be rejected");
        }
    }

    @Test
    @DisplayName("targetsFrom enumerates exactly the documented fan-outs")
    void targetsFromFanOuts() {
        assertEquals(EnumSet.of(PartnerStatus.SUSPENDED, PartnerStatus.TERMINATED),
                PartnerStatusTransitionTable.targetsFrom(PartnerStatus.LIVE));
        assertEquals(EnumSet.of(PartnerStatus.LIVE, PartnerStatus.TERMINATED),
                PartnerStatusTransitionTable.targetsFrom(PartnerStatus.SUSPENDED));
        assertEquals(EnumSet.of(PartnerStatus.KYB_APPROVED, PartnerStatus.ONBOARDING),
                PartnerStatusTransitionTable.targetsFrom(PartnerStatus.KYB_PENDING));
        assertEquals(EnumSet.of(PartnerStatus.ONBOARDING),
                PartnerStatusTransitionTable.targetsFrom(PartnerStatus.DRAFT));
    }

    @Test
    @DisplayName("the table carries exactly the twelve ADR-011 edges with guards")
    void tableShape() {
        List<PartnerStatusTransitionTable.Transition> all = PartnerStatusTransitionTable.all();
        assertEquals(12, all.size());
        // Every edge carries a non-blank guard description.
        for (PartnerStatusTransitionTable.Transition t : all) {
            assertTrue(t.guard() != null && !t.guard().isBlank(),
                    "edge " + t.from() + " -> " + t.to() + " must carry a guard");
        }
        // find() surfaces the activation-gate guard on UAT -> LIVE.
        assertTrue(PartnerStatusTransitionTable
                .find(PartnerStatus.UAT, PartnerStatus.LIVE)
                .orElseThrow().guard().contains("activation gate"));
    }

    @Test
    @DisplayName("every PartnerStatus except TERMINATED has at least one outbound edge")
    void everyNonTerminalStateHasAnOutboundEdge() {
        Set<PartnerStatus> terminal = EnumSet.of(PartnerStatus.TERMINATED);
        for (PartnerStatus from : PartnerStatus.values()) {
            if (terminal.contains(from)) {
                continue;
            }
            assertFalse(PartnerStatusTransitionTable.targetsFrom(from).isEmpty(),
                    from + " must have an outbound edge");
        }
    }
}
