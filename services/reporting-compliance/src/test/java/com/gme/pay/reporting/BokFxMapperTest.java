package com.gme.pay.reporting;

import com.gme.pay.reporting.domain.BokFxMapper;
import com.gme.pay.reporting.domain.BokFxRecord;
import com.gme.pay.reporting.domain.BokReportType;
import com.gme.pay.reporting.domain.CommittedTransaction;
import com.gme.pay.reporting.domain.TransactionDirection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Plain JUnit 5 unit tests for {@link BokFxMapper}.
 * No Spring context, no Docker, no Testcontainers.
 *
 * <p>Key invariant being tested: offer_rate_coll (BOK FX1015 field #14) is copied
 * verbatim from the committed transaction.  It was computed by the rate engine as:
 * <pre>
 *   offer_rate_coll = send_amount / (collection_usd - collection_margin_usd)
 * </pre>
 * and locked at CommitTransaction time.
 */
class BokFxMapperTest {

    private BokFxMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new BokFxMapper();
    }

    // -------------------------------------------------------------------------
    // Helper factory
    // -------------------------------------------------------------------------

    /**
     * Builds a cross-border INBOUND committed transaction with realistic BOK values.
     * Based on the standard ZeroPay inbound scenario from spec 13.7-T16:
     *   target_payout=50000 KRW, cost_rate_pay=1350, m_a=0.01, m_b=0.015,
     *   service_charge=1.00 USD.
     *   offerRateColl ≈ 1.01010103 (BOK FX1015 field #14)
     *   crossRate     ≈ 1316.25 KRW/USD
     */
    private CommittedTransaction inboundTxn(Instant committedAt) {
        return new CommittedTransaction(
                1001L,
                "TXN-2026-001",
                TransactionDirection.INBOUND,
                false,                                      // cross-border
                new BigDecimal("1.01010103"),               // offerRateColl – BOK FX1015 #14
                new BigDecimal("1316.25000000"),            // crossRate
                new BigDecimal("38.9867"),                  // collectionAmount (USD)
                "USD",
                new BigDecimal("50000"),                    // payoutAmount (KRW)
                "KRW",
                new BigDecimal("37.0370"),                  // usdAmount (payout_usd_cost)
                committedAt,
                42L                                         // partnerId
        );
    }

    private CommittedTransaction outboundTxn() {
        return new CommittedTransaction(
                1002L,
                "TXN-2026-002",
                TransactionDirection.OUTBOUND,
                false,
                new BigDecimal("1.00500000"),
                new BigDecimal("0.99502488"),
                new BigDecimal("105.00"),
                "USD",
                new BigDecimal("100.00"),
                "USD",
                new BigDecimal("104.47"),
                Instant.parse("2026-01-15T10:00:00Z"),
                43L
        );
    }

    private CommittedTransaction domesticTxn() {
        return new CommittedTransaction(
                1003L,
                "TXN-2026-003",
                TransactionDirection.DOMESTIC,
                true,       // sameCcyShortcircuit=true
                null,       // offerRateColl = null (no FX)
                null,       // crossRate = null
                new BigDecimal("15500"),    // collectionAmount KRW
                "KRW",
                new BigDecimal("15000"),    // payoutAmount KRW
                "KRW",
                null,
                Instant.parse("2026-01-15T10:00:00Z"),
                42L
        );
    }

    // =========================================================================
    // TEST 1: INBOUND → FX1015, offerRateColl copied verbatim
    // =========================================================================

    @Test
    @DisplayName("Inbound cross-border transaction maps to FX1015 with offerRateColl = field #14")
    void inbound_mapsToFx1015_andCopiesOfferRateColl() {
        // committed_at = 2026-01-15 10:00 UTC = 2026-01-15 19:00 KST → report_date 2026-01-15
        CommittedTransaction txn = inboundTxn(Instant.parse("2026-01-15T10:00:00Z"));

        BokFxRecord record = mapper.toRecord(txn);

        // Report type
        assertEquals(BokReportType.FX1015, record.getReportType(),
                "INBOUND must map to FX1015");

        // BOK FX1015 field #14 — offer_rate_coll is copied verbatim from the locked transaction value
        assertNotNull(record.getOfferRateColl(), "offerRateColl (BOK FX1015 #14) must not be null for cross-border");
        assertEquals(0, new BigDecimal("1.01010103").compareTo(record.getOfferRateColl()),
                "offerRateColl must equal the locked value from the transaction");

        // Other field checks
        assertEquals(1001L, record.getTxnId());
        assertEquals("TXN-2026-001", record.getTxnRef());
        assertEquals(42L, record.getPartnerId());
        assertEquals(LocalDate.of(2026, 1, 15), record.getReportDate());
        assertEquals("PENDING", record.getSubmissionStatus());
        assertEquals(new BigDecimal("50000"), record.getPayoutAmount());
        assertEquals("KRW", record.getPayoutCcy());
    }

    // =========================================================================
    // TEST 2: OUTBOUND → FX1014
    // =========================================================================

    @Test
    @DisplayName("Outbound cross-border transaction maps to FX1014")
    void outbound_mapsToFx1014() {
        BokFxRecord record = mapper.toRecord(outboundTxn());

        assertEquals(BokReportType.FX1014, record.getReportType(),
                "OUTBOUND must map to FX1014");
        assertEquals(1002L, record.getTxnId());
        assertNotNull(record.getOfferRateColl());
        assertEquals(0, new BigDecimal("1.00500000").compareTo(record.getOfferRateColl()));
    }

    // =========================================================================
    // TEST 3: HUB → FX1015 (provisional, pending OI-03)
    // =========================================================================

    @Test
    @DisplayName("HUB direction defaults to FX1015 pending OI-03 confirmation")
    void hub_defaultsToFx1015_pendingOi03() {
        CommittedTransaction hubTxn = new CommittedTransaction(
                1004L, "TXN-2026-004",
                TransactionDirection.HUB,
                false,
                new BigDecimal("1.01000000"),
                new BigDecimal("1300.00000000"),
                new BigDecimal("40.00"), "USD",
                new BigDecimal("52000"), "KRW",
                new BigDecimal("38.50"),
                Instant.parse("2026-01-15T10:00:00Z"),
                44L);

        BokFxRecord record = mapper.toRecord(hubTxn);

        assertEquals(BokReportType.FX1015, record.getReportType(),
                "HUB defaults to FX1015 pending OI-03");
    }

    // =========================================================================
    // TEST 4: Domestic / same-currency → IllegalArgumentException (BOK exempt)
    // =========================================================================

    @Test
    @DisplayName("Domestic same-currency transaction throws — BOK exempt")
    void domestic_throwsIllegalArgument() {
        CommittedTransaction txn = domesticTxn();

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> mapper.toRecord(txn),
                "Domestic transactions must be rejected for BOK reporting");

        assertTrue(ex.getMessage().contains("exempt"),
                "Exception message should mention 'exempt'");
    }

    // =========================================================================
    // TEST 5: DOMESTIC direction flag (even without sameCcyShortcircuit) → exempt
    // =========================================================================

    @Test
    @DisplayName("DOMESTIC direction alone is sufficient to exempt from BOK reporting")
    void domesticDirection_aloneIsExempt() {
        CommittedTransaction domesticWithFxFlag = new CommittedTransaction(
                1005L, "TXN-2026-005",
                TransactionDirection.DOMESTIC,
                false,  // sameCcyShortcircuit flag is false but direction is DOMESTIC
                null, null,
                new BigDecimal("15500"), "KRW",
                new BigDecimal("15000"), "KRW",
                null,
                Instant.parse("2026-01-15T10:00:00Z"),
                42L);

        assertThrows(IllegalArgumentException.class, () -> mapper.toRecord(domesticWithFxFlag));
    }

    // =========================================================================
    // TEST 6: report_date uses KST (UTC+9) — edge case midnight crossover
    // =========================================================================

    @Test
    @DisplayName("report_date is the KST date of committedAt — UTC midnight crossover")
    void reportDate_usesKstTimezone() {
        // 2026-01-15 15:30:00 UTC = 2026-01-16 00:30:00 KST → report_date = 2026-01-16
        CommittedTransaction txn = inboundTxn(Instant.parse("2026-01-15T15:30:00Z"));
        BokFxRecord record = mapper.toRecord(txn);

        assertEquals(LocalDate.of(2026, 1, 16), record.getReportDate(),
                "15:30 UTC is next day in KST (UTC+9), so report_date must be 2026-01-16");
    }

    @Test
    @DisplayName("report_date is the KST date — before midnight crossover")
    void reportDate_beforeMidnightKst() {
        // 2026-01-15 14:59:00 UTC = 2026-01-15 23:59:00 KST → report_date = 2026-01-15
        CommittedTransaction txn = inboundTxn(Instant.parse("2026-01-15T14:59:00Z"));
        BokFxRecord record = mapper.toRecord(txn);

        assertEquals(LocalDate.of(2026, 1, 15), record.getReportDate(),
                "14:59 UTC is 23:59 KST same day, so report_date must be 2026-01-15");
    }

    // =========================================================================
    // TEST 7: null transaction → NullPointerException
    // =========================================================================

    @Test
    @DisplayName("Null transaction argument throws NullPointerException")
    void nullTransaction_throwsNpe() {
        assertThrows(NullPointerException.class, () -> mapper.toRecord(null));
    }

    // =========================================================================
    // TEST 8: resolveReportType static helper
    // =========================================================================

    @Test
    @DisplayName("resolveReportType: DOMESTIC direction throws IllegalArgumentException")
    void resolveReportType_domestic_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> BokFxMapper.resolveReportType(TransactionDirection.DOMESTIC));
    }
}
