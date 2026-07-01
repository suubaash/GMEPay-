package com.gme.pay.txn.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the committed-FX projection math on {@link Transaction} (subash-fx formulas).
 * No Spring, no DB.
 *
 * <ul>
 *   <li>offerRateColl = send_amount / (collection_usd - collection_margin_usd)  [FX1015 #14]</li>
 *   <li>crossRate     = target_payout / send_amount</li>
 * </ul>
 */
class CommittedFxMathTest {

    /** A cross-border OVERSEAS txn: collect 100 USD pool, pay out 130,000 KRW. */
    private static Transaction crossBorder() {
        Transaction txn = new Transaction(
                700L, "PTX-1", "zeropay_kr", "OUTBOUND", "CPM",
                new BigDecimal("130000.00000000"), "KRW",   // targetPayout / payoutCurrency
                new BigDecimal("11000000.00000000"), "IDR", // collectionAmount / collectionCurrency
                "M-1", "Q-1");
        // prefundDeductedUsd is the USD pool moved (collection_usd proxy).
        txn.applyStatusPatch("SCH-1", "AP-1", new BigDecimal("673.07690000"),
                Instant.now(), null, null, null);
        return txn;
    }

    @Test
    @DisplayName("offerRateColl = send_amount / (collection_usd - collection_margin_usd)")
    void offerRateCollFormula() {
        // send_amount 10,850,000 IDR ; collection_usd 673.0769 ; collection_margin 6.7308
        BigDecimal offer = Transaction.computeOfferRateColl(
                new BigDecimal("10850000"), new BigDecimal("673.0769"),
                new BigDecimal("6.7308"), false);
        // 10,850,000 / (673.0769 - 6.7308) = 10,850,000 / 666.3461 = 16,282.82959861
        assertNotNull(offer);
        assertEquals(new BigDecimal("16282.82959861"), offer);
    }

    @Test
    @DisplayName("offerRateColl collapses to send_amount/collection_usd when margin is null/zero")
    void offerRateCollZeroMargin() {
        BigDecimal offer = Transaction.computeOfferRateColl(
                new BigDecimal("10850000"), new BigDecimal("673.0769"), null, false);
        // 10,850,000 / 673.0769 = 16,120.00055269
        assertEquals(new BigDecimal("16120.00055269"), offer);
    }

    @Test
    @DisplayName("crossRate = target_payout / send_amount")
    void crossRateFormula() {
        BigDecimal cross = Transaction.computeCrossRate(
                new BigDecimal("10850000"), new BigDecimal("989423"), false);
        // 989,423 / 10,850,000 = 0.09119106...
        assertEquals(new BigDecimal("0.09119106"), cross);
    }

    @Test
    @DisplayName("same-currency short-circuit yields null rates")
    void sameCcyNullRates() {
        assertNull(Transaction.computeOfferRateColl(
                new BigDecimal("100"), new BigDecimal("100"), BigDecimal.ZERO, true));
        assertNull(Transaction.computeCrossRate(
                new BigDecimal("100"), new BigDecimal("100"), true));
    }

    @Test
    @DisplayName("non-positive denominator (margin >= collection_usd) yields null offerRateColl")
    void nonPositiveDenominator() {
        assertNull(Transaction.computeOfferRateColl(
                new BigDecimal("100"), new BigDecimal("5"), new BigDecimal("5"), false));
    }

    @Test
    @DisplayName("captureCommittedFxAtCommit populates the projection from aggregate data")
    void captureAtCommit() {
        Transaction txn = crossBorder();
        Instant when = Instant.now();
        txn.captureCommittedFxAtCommit(null, null, when);

        assertFalse(txn.sameCcyShortcircuit());
        assertEquals(when, txn.committedAt());
        // usdAmount = prefundDeductedUsd
        assertEquals(0, txn.usdAmount().compareTo(new BigDecimal("673.07690000")));
        // crossRate = 130000 / 11000000
        assertEquals(0, txn.crossRate().compareTo(new BigDecimal("130000").divide(
                new BigDecimal("11000000"), 8, java.math.RoundingMode.HALF_UP)));
        // offerRateColl present (send_amount = collectionAmount, zero margin)
        assertNotNull(txn.offerRateColl());
    }

    @Test
    @DisplayName("Wave-3: persisted margin + real collectionUsd → margin-accurate offerRateColl at commit")
    void captureUsesPersistedMarginAndCollectionUsd() {
        Transaction txn = new Transaction(
                700L, "PTX-3", "zeropay_kr", "OUTBOUND", "CPM",
                new BigDecimal("130000.00000000"), "KRW",
                new BigDecimal("10850000.00000000"), "IDR",   // send_amount = collectionAmount
                "M-3", "Q-3");
        // prefund proxy deliberately DIFFERENT from the real collection_usd, to prove the real
        // pool value is preferred over the proxy.
        txn.applyStatusPatch("SCH-3", "AP-3", new BigDecimal("999.99999999"),
                Instant.now(), null, null, null);
        // Persist the rate-lock pool: real collection_usd 673.0769, collection margin 6.7308.
        txn.applyRateLockPool(new BigDecimal("6.7308"), new BigDecimal("3.1500"),
                new BigDecimal("673.0769"), new BigDecimal("0.00148000"),
                new BigDecimal("0.00752000"), new BigDecimal("130.0000"));

        // SM passes null margins → falls back to the persisted aggregate margin/collectionUsd.
        txn.captureCommittedFxAtCommit(null, null, Instant.now());

        // offerRateColl = 10,850,000 / (673.0769 - 6.7308) = 16,282.82959861  (NON-zero margin)
        assertEquals(new BigDecimal("16282.82959861"), txn.offerRateColl());
        // usdAmount uses the REAL collection_usd (673.0769), not the prefund proxy (999.99999999).
        assertEquals(0, txn.usdAmount().compareTo(new BigDecimal("673.0769")));
        assertEquals(0, txn.collectionMarginUsd().compareTo(new BigDecimal("6.7308")));
    }

    @Test
    @DisplayName("Wave-3: no pool fields (legacy row) → zero-margin fallback over prefund proxy")
    void captureFallsBackToZeroMarginWhenPoolAbsent() {
        Transaction txn = crossBorder(); // prefundDeductedUsd=673.0769, no pool
        txn.captureCommittedFxAtCommit(null, null, Instant.now());
        // collapses to send_amount / collection_usd with zero margin
        // 11,000,000 / 673.0769 = 16,343.36... ; just assert non-null and margin null
        assertNotNull(txn.offerRateColl());
        assertNull(txn.collectionMarginUsd());
        assertEquals(0, txn.usdAmount().compareTo(new BigDecimal("673.07690000")));
    }

    @Test
    @DisplayName("same-currency txn at commit records usd/committedAt but null FX rates")
    void captureSameCcy() {
        Transaction txn = new Transaction(
                701L, "PTX-2", "zeropay_kr", "DOMESTIC", "CPM",
                new BigDecimal("50000.00000000"), "KRW",
                new BigDecimal("50000.00000000"), "KRW",
                "M-2", "Q-2");
        txn.captureCommittedFxAtCommit(null, null, Instant.now());

        assertTrue(txn.sameCcyShortcircuit());
        assertNull(txn.offerRateColl());
        assertNull(txn.crossRate());
        assertNotNull(txn.committedAt());
    }
}
