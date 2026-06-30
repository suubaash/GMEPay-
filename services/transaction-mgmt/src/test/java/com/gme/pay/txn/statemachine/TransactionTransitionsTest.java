package com.gme.pay.txn.statemachine;

import com.gme.pay.txn.domain.model.TransactionStatus;
import com.gme.pay.txn.domain.statemachine.TransactionTransitions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 unit tests for the permitted-transition table.
 * No Spring context, no Docker, no Testcontainers – fully deterministic.
 */
class TransactionTransitionsTest {

    // ------------------------------------------------------------------
    // Allowed transitions (wave scope: CREATED → PENDING_DEBIT → APPROVED/FAILED/CANCELLED)
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} → {1} should be ALLOWED")
    @CsvSource({
            "CREATED, PENDING_DEBIT",
            "CREATED, SCHEME_SENT",
            "CREATED, APPROVED",
            "CREATED, FAILED",
            "CREATED, CANCELLED",
            "PENDING_DEBIT, SCHEME_SENT",
            "PENDING_DEBIT, APPROVED",
            "PENDING_DEBIT, FAILED",
            "PENDING_DEBIT, CANCELLED",
            // Scheme dispatch → synchronous outcomes / timeout
            "SCHEME_SENT, APPROVED",
            "SCHEME_SENT, FAILED",
            "SCHEME_SENT, UNCERTAIN",
            // Batch reconciliation resolves an UNCERTAIN transaction
            "UNCERTAIN, APPROVED",
            "UNCERTAIN, FAILED",
            // P1-2: APPROVED is reversible (same-day cancel) / refundable
            "APPROVED, REVERSED",
            "APPROVED, REFUNDED",
    })
    @DisplayName("Allowed transitions are recognised")
    void allowedTransitions(TransactionStatus from, TransactionStatus to) {
        assertTrue(
                TransactionTransitions.isAllowed(from, to),
                () -> from + " → " + to + " should be allowed but was not");
    }

    // ------------------------------------------------------------------
    // Forbidden transitions
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "{0} → {1} should be FORBIDDEN")
    @CsvSource({
            // Self-transitions are always forbidden
            "CREATED,       CREATED",
            "PENDING_DEBIT, PENDING_DEBIT",
            "APPROVED,      APPROVED",
            "FAILED,        FAILED",
            "CANCELLED,     CANCELLED",
            // No outgoing from terminal states
            "APPROVED,   CREATED",
            "APPROVED,   PENDING_DEBIT",
            "APPROVED,   FAILED",
            "APPROVED,   CANCELLED",
            "FAILED,     CREATED",
            "FAILED,     PENDING_DEBIT",
            "CANCELLED,  CREATED",
            "CANCELLED,  PENDING_DEBIT",
            // Backward transitions
            "PENDING_DEBIT, CREATED",
            "SCHEME_SENT,   PENDING_DEBIT",
            "SCHEME_SENT,   CREATED",
            "UNCERTAIN,     SCHEME_SENT",
            // New-state self-transitions and disallowed pairs
            "SCHEME_SENT,   SCHEME_SENT",
            "UNCERTAIN,     UNCERTAIN",
            "UNCERTAIN,     CANCELLED",
            "SCHEME_SENT,   CANCELLED",
            "SCHEME_SENT,   REVERSED",
    })
    @DisplayName("Forbidden transitions are rejected")
    void forbiddenTransitions(TransactionStatus from, TransactionStatus to) {
        assertFalse(
                TransactionTransitions.isAllowed(from, to),
                () -> from + " → " + to + " should be forbidden but was allowed");
    }

    // ------------------------------------------------------------------
    // Terminal states have no outgoing transitions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Sink states have empty allowed-from sets")
    void sinkStatesHaveNoOutgoing() {
        // APPROVED is terminal for the expiry sweeper (isTerminal()=true) but is reversible/refundable
        // in the transition table (P1-2), so it has outgoing edges. The true sinks are these four.
        for (TransactionStatus sink : new TransactionStatus[]{
                TransactionStatus.FAILED,
                TransactionStatus.CANCELLED,
                TransactionStatus.REVERSED,
                TransactionStatus.REFUNDED}) {
            Set<TransactionStatus> outgoing = TransactionTransitions.allowedFrom(sink);
            assertTrue(outgoing.isEmpty(),
                    () -> "Expected no outgoing transitions from " + sink + " but got: " + outgoing);
        }
    }

    // ------------------------------------------------------------------
    // All states are present as keys
    // ------------------------------------------------------------------

    @Test
    @DisplayName("All TransactionStatus values are present as keys in the transition map")
    void allStatusesPresent() {
        for (TransactionStatus s : TransactionStatus.values()) {
            assertNotNull(
                    TransactionTransitions.allowedFrom(s),
                    () -> s + " has no entry in the transition map");
        }
    }

    // ------------------------------------------------------------------
    // Regression: CREATED is non-terminal, PENDING_DEBIT is non-terminal
    // ------------------------------------------------------------------

    @Test
    @DisplayName("CREATED, PENDING_DEBIT, SCHEME_SENT, UNCERTAIN are not terminal")
    void nonTerminalStatuses() {
        assertFalse(TransactionStatus.CREATED.isTerminal(),      "CREATED must not be terminal");
        assertFalse(TransactionStatus.PENDING_DEBIT.isTerminal(),"PENDING_DEBIT must not be terminal");
        assertFalse(TransactionStatus.SCHEME_SENT.isTerminal(),  "SCHEME_SENT must not be terminal");
        assertFalse(TransactionStatus.UNCERTAIN.isTerminal(),    "UNCERTAIN must not be terminal");
    }

    @Test
    @DisplayName("SCHEME_SENT and UNCERTAIN have the expected outgoing edges")
    void schemeSentAndUncertainOutgoing() {
        assertEquals(
                Set.of(TransactionStatus.APPROVED, TransactionStatus.FAILED, TransactionStatus.UNCERTAIN),
                TransactionTransitions.allowedFrom(TransactionStatus.SCHEME_SENT));
        assertEquals(
                Set.of(TransactionStatus.APPROVED, TransactionStatus.FAILED),
                TransactionTransitions.allowedFrom(TransactionStatus.UNCERTAIN));
    }

    @Test
    @DisplayName("APPROVED, FAILED, CANCELLED, REVERSED, REFUNDED are terminal (sweeper does not expire them)")
    void terminalStatuses() {
        assertTrue(TransactionStatus.APPROVED.isTerminal(),  "APPROVED must be terminal");
        assertTrue(TransactionStatus.FAILED.isTerminal(),    "FAILED must be terminal");
        assertTrue(TransactionStatus.CANCELLED.isTerminal(), "CANCELLED must be terminal");
        assertTrue(TransactionStatus.REVERSED.isTerminal(),  "REVERSED must be terminal");
        assertTrue(TransactionStatus.REFUNDED.isTerminal(),  "REFUNDED must be terminal");
    }
}
