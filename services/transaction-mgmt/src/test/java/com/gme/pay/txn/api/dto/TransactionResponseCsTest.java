package com.gme.pay.txn.api.dto;

import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CS quick-wins unit tests for {@link TransactionResponse}: verifies the additive customer-support
 * fields (failureReason, statusLabel, declineReasonText, userRef) are populated on the DTO, and that
 * {@code statusHistory} is derived into an ordered, non-null timeline for a committed and a failed
 * transaction. Constructs domain aggregates directly (no Spring/H2) so the mapping is grounded in
 * the real aggregate. Wire-level JSON serialization is covered end-to-end in {@code TransactionContractIT}.
 */
class TransactionResponseCsTest {

    /** Rehydrates a domain txn in a chosen state with the given timestamps + failure reason. */
    private static Transaction rehydrate(TransactionStatus status, Instant createdAt,
                                         Instant approvedAt, String failureReason, String userRef) {
        Transaction txn = new Transaction(
                "txn-ref-1", "PARTNER-REF-1",
                new BigDecimal("100.00"), "USD",
                new BigDecimal("130000.00"), "KRW",
                status, createdAt, createdAt,
                null, (RoundingMode) null, null,
                42L, "PARTNER-REF-1", "zeropay_kr", "INBOUND", "QR",
                "KRW", new BigDecimal("100.00"), "USD", "MERCH-1", "Q-1", "pay-1",
                null, null, null, approvedAt, failureReason);
        txn.applyUserRef(userRef);
        return txn;
    }

    @Test
    @DisplayName("failureReason + statusLabel + declineReasonText populated for a FAILED txn")
    void failedTxnExposesDeclineFields() {
        Instant created = Instant.parse("2026-07-01T00:00:00Z");
        Transaction txn = rehydrate(TransactionStatus.FAILED, created, null,
                "APPROVAL_TIMEOUT", "WALLET-XYZ");

        TransactionResponse resp = TransactionResponse.from(txn);

        assertEquals("APPROVAL_TIMEOUT", resp.failureReason());
        assertEquals("Declined", resp.statusLabel());
        assertEquals("The payment timed out waiting for the payment network to confirm.",
                resp.declineReasonText());
        assertEquals("WALLET-XYZ", resp.userRef());
    }

    @Test
    @DisplayName("statusLabel maps APPROVED to a plain-language label; no decline fields when not failed")
    void approvedTxnHasLabelNoDecline() {
        Instant created = Instant.parse("2026-07-01T00:00:00Z");
        Instant approved = Instant.parse("2026-07-01T01:00:00Z");
        Transaction txn = rehydrate(TransactionStatus.APPROVED, created, approved, null, "WALLET-1");

        TransactionResponse resp = TransactionResponse.from(txn);

        assertEquals("Payment approved", resp.statusLabel());
        // NON_NULL: no failure fields for a non-failed txn.
        assertNull(resp.failureReason());
        assertNull(resp.declineReasonText());
    }

    @Test
    @DisplayName("statusHistory is an ordered non-null timeline for a committed (APPROVED) txn")
    void statusHistoryForApproved() {
        Instant created = Instant.parse("2026-07-01T00:00:00Z");
        Instant approved = Instant.parse("2026-07-01T01:00:00Z");
        Transaction txn = rehydrate(TransactionStatus.APPROVED, created, approved, null, null);

        List<TransactionResponse.StatusEntry> history = TransactionResponse.from(txn).statusHistory();

        assertNotNull(history, "statusHistory must not be null");
        assertEquals(2, history.size());
        assertEquals("CREATED", history.get(0).status());
        assertEquals("Payment created", history.get(0).statusLabel());
        assertEquals(created, history.get(0).at());
        assertEquals("APPROVED", history.get(1).status());
        assertEquals("Payment approved", history.get(1).statusLabel());
        assertEquals(approved, history.get(1).at());
        // Ordered oldest-first.
        assertTrue(history.get(0).at().isBefore(history.get(1).at()));
    }

    @Test
    @DisplayName("statusHistory ends with a FAILED entry carrying the decline reason as its note")
    void statusHistoryForFailed() {
        Instant created = Instant.parse("2026-07-01T00:00:00Z");
        Transaction txn = rehydrate(TransactionStatus.FAILED, created, null, "SCHEME_REJECTED", null);

        List<TransactionResponse.StatusEntry> history = TransactionResponse.from(txn).statusHistory();

        assertNotNull(history);
        assertFalse(history.isEmpty());
        TransactionResponse.StatusEntry last = history.get(history.size() - 1);
        assertEquals("FAILED", last.status());
        assertEquals("Declined", last.statusLabel());
        assertEquals("The payment was declined by the payment network.", last.note());
    }
}
