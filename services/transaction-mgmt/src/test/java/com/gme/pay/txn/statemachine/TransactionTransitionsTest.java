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
            "CREATED, APPROVED",
            "CREATED, FAILED",
            "CREATED, CANCELLED",
            "PENDING_DEBIT, APPROVED",
            "PENDING_DEBIT, FAILED",
            "PENDING_DEBIT, CANCELLED",
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
    @DisplayName("CREATED and PENDING_DEBIT are not terminal")
    void nonTerminalStatuses() {
        assertFalse(TransactionStatus.CREATED.isTerminal(),      "CREATED must not be terminal");
        assertFalse(TransactionStatus.PENDING_DEBIT.isTerminal(),"PENDING_DEBIT must not be terminal");
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
