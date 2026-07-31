package com.gme.pay.ledger.revenue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2-4 atomicity: the revenue record and its double-entry journal are written in ONE transaction, so
 * they can never diverge. If the journal cannot be persisted, the revenue record must not survive
 * either — otherwise the single-entry/double-entry split this gap was about would simply reappear as a
 * partial write.
 *
 * <p>The failure is induced with real data rather than a mock: {@code ledger_entries.amount} is
 * {@code NUMERIC(20,8)} while {@code revenue_records.fx_margin_usd} is {@code NUMERIC(20,4)}, so an
 * amount with 14 integer digits fits the record column and overflows the ledger column. That makes the
 * journal insert — and only the journal insert — fail inside the capture transaction.
 *
 * <p>Runs as a {@code @SpringBootTest} deliberately NOT annotated {@link Transactional}, so the real
 * transaction boundary is the one {@code RevenueCaptureService.capture} declares (a test-managed
 * transaction would swallow the rollback being asserted).
 */
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
class RevenueCaptureAtomicityTest {

    @Autowired
    private RevenueCaptureService capture;

    @Autowired
    private RevenueRecordStore store;

    @Test
    void aFailedJournalRollsBackTheRevenueRecord() {
        String txnRef = "TXN-ATOMIC-1";
        // 14 integer digits: fits NUMERIC(20,4) (18 total) but not NUMERIC(20,8) (22 total).
        BigDecimal overflowsTheLedgerColumn = new BigDecimal("99999999999999.0000");

        assertThrows(Exception.class, () -> capture.capture(
                txnRef, 7L, 1L, LocalDate.of(2026, 7, 15),
                overflowsTheLedgerColumn, BigDecimal.ZERO,
                BigDecimal.ZERO, "USD", new BigDecimal("0.7000")));

        assertTrue(store.findByTxnRef(txnRef).isEmpty(),
                "the revenue record must roll back with its journal — a record without a journal is "
                        + "exactly the single-entry state T2-4 closes");
    }

    @Test
    void captureIsTransactional() {
        // Pins the annotation itself: without it the two writes would commit independently and the
        // rollback asserted above would silently stop happening.
        Method method = java.util.Arrays.stream(RevenueCaptureService.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("capture"))
                .findFirst()
                .orElseThrow();
        assertNotNull(method.getAnnotation(Transactional.class),
                "RevenueCaptureService.capture must be @Transactional so record + journal commit together");
    }

    @Test
    void aSuccessfulCaptureCommitsBoth() {
        String txnRef = "TXN-ATOMIC-OK";
        capture.capture(txnRef, 7L, 1L, LocalDate.of(2026, 7, 15),
                new BigDecimal("1.0000"), BigDecimal.ZERO,
                new BigDecimal("500.0000"), "KRW", new BigDecimal("0.7000"));
        assertFalse(store.findByTxnRef(txnRef).isEmpty(), "the happy path still commits the record");
    }
}
