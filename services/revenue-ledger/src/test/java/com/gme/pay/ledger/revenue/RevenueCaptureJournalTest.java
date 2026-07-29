package com.gme.pay.ledger.revenue;

import com.gme.pay.ledger.domain.ledger.LedgerPostingService;
import com.gme.pay.ledger.domain.model.EntryType;
import com.gme.pay.ledger.fees.SchemeFeeSplitCalculator;
import com.gme.pay.ledger.outbox.OutboxWriter;
import com.gme.pay.ledger.persistence.JournalEntityRepository;
import com.gme.pay.ledger.persistence.JpaJournalStore;
import com.gme.pay.ledger.persistence.JpaRevenueRecordStore;
import com.gme.pay.ledger.persistence.LedgerEntryEntity;
import com.gme.pay.ledger.persistence.LedgerEntryEntityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * T2-4: proves the main P&amp;L now reaches the double-entry journal. Every {@code POST /v1/revenue/capture}
 * (and every {@code payment.approved} consume) writes the revenue record AND balanced journal lines for
 * each revenue type it carries, in the same transaction, idempotently.
 *
 * <p>Runs the REAL {@link RevenueCaptureService} over the real H2 schema (Flyway V001/V002/V004) — no
 * broker, no Docker — so the accounts, sides and amounts asserted here are the ones production posts.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({JpaJournalStore.class, LedgerPostingService.class, SchemeFeeSplitCalculator.class,
        OutboxWriter.class, JpaRevenueRecordStore.class, RevenueCaptureService.class})
class RevenueCaptureJournalTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 15);

    @Autowired
    private RevenueCaptureService capture;

    @Autowired
    private LedgerEntryEntityRepository entries;

    @Autowired
    private JournalEntityRepository journals;

    @Autowired
    private JpaRevenueRecordStore recordStore;

    @Test
    void crossBorderCapture_journalsFxMarginAndServiceCharge_balancedPerCurrency() {
        String ref = "TXN-CAP-XB";
        // fxMarginUsd = 1.0000 + 0.5000 = 1.5000 USD; service charge 500 KRW.
        capture.capture(ref, 7L, 1L, DATE,
                new BigDecimal("1.0000"), new BigDecimal("0.5000"),
                new BigDecimal("500.0000"), "KRW", new BigDecimal("0.7000"));

        List<LedgerEntryEntity> lines = entries.findByReferenceOrderByIdAsc(ref);
        assertEquals(4, lines.size(), "one DR/CR pair per revenue type (USD margin + KRW service charge)");

        // The FX-margin leg: DEBIT RECEIVABLE_PARTNER / CREDIT REVENUE_FX_MARGIN, both USD.
        assertAmount("1.50", amount(lines, "RECEIVABLE_PARTNER", "USD", EntryType.DEBIT));
        assertAmount("1.50", amount(lines, "REVENUE_FX_MARGIN", "USD", EntryType.CREDIT));
        // The service-charge leg: DEBIT RECEIVABLE_PARTNER / CREDIT REVENUE_SERVICE_CHARGE, both KRW.
        assertAmount("500", amount(lines, "RECEIVABLE_PARTNER", "KRW", EntryType.DEBIT));
        assertAmount("500", amount(lines, "REVENUE_SERVICE_CHARGE", "KRW", EntryType.CREDIT));

        assertBalancedPerCurrency(lines);
    }

    @Test
    void domesticCapture_zeroMargin_journalsOnlyTheServiceCharge() {
        String ref = "TXN-CAP-DOM";
        capture.capture(ref, 7L, 1L, DATE,
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("500.0000"), "KRW", new BigDecimal("0.7000"));

        List<LedgerEntryEntity> lines = entries.findByReferenceOrderByIdAsc(ref);
        assertEquals(2, lines.size(), "a zero FX margin must not produce zero-amount margin lines");
        assertAmount("500", amount(lines, "REVENUE_SERVICE_CHARGE", "KRW", EntryType.CREDIT));
        assertBalancedPerCurrency(lines);
    }

    @Test
    void replayedCapture_doesNotDoubleBook() {
        String ref = "TXN-CAP-REPLAY";
        capture.capture(ref, 7L, 1L, DATE, new BigDecimal("1.0000"), BigDecimal.ZERO,
                new BigDecimal("500.0000"), "KRW", new BigDecimal("0.7000"));
        int linesAfterFirst = entries.findByReferenceOrderByIdAsc(ref).size();
        long journalsAfterFirst = journals.findByReferenceOrderByPostedAtAsc(ref).size();

        // Three more replays, as an at-least-once Kafka redelivery or a client retry would do.
        for (int i = 0; i < 3; i++) {
            var result = capture.capture(ref, 7L, 1L, DATE, new BigDecimal("1.0000"), BigDecimal.ZERO,
                    new BigDecimal("500.0000"), "KRW", new BigDecimal("0.7000"));
            assertEquals(false, result.created(), "replay must not create a second revenue record");
        }

        assertEquals(linesAfterFirst, entries.findByReferenceOrderByIdAsc(ref).size(),
                "replays must not add ledger lines");
        assertEquals(journalsAfterFirst, journals.findByReferenceOrderByPostedAtAsc(ref).size(),
                "replays must not add journals");
    }

    @Test
    void zeroRevenueCapture_postsNoNominalJournalNoise() {
        String ref = "TXN-CAP-ZERO";
        capture.capture(ref, 7L, 1L, DATE, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, "KRW", new BigDecimal("0.7000"));

        assertTrue(entries.findByReferenceOrderByIdAsc(ref).isEmpty(),
                "a genuinely zero-revenue transaction must not post a nominal zero journal (CFO#14); "
                        + "it is reported as zeroAmount by the reconciliation self-check instead");
    }

    /** A record captured before T2-4 has no journal; any later replay must back-fill exactly one. */
    @Test
    void replayBackFillsAJournalForARecordThatHasNone() {
        String ref = "TXN-CAP-BACKFILL";
        // Simulate the pre-T2-4 state: the record exists but no journal was ever posted for it.
        RevenueRecord legacy = RevenueRecord.of(ref, 7L, 1L, DATE,
                new BigDecimal("2.0000"), BigDecimal.ZERO,
                new BigDecimal("500.0000"), "KRW", new BigDecimal("0.7000"));
        recordStore.save(legacy);
        assertTrue(entries.findByReferenceOrderByIdAsc(ref).isEmpty(), "precondition: not journalled");

        capture.capture(ref, 7L, 1L, DATE, new BigDecimal("2.0000"), BigDecimal.ZERO,
                new BigDecimal("500.0000"), "KRW", new BigDecimal("0.7000"));
        assertEquals(4, entries.findByReferenceOrderByIdAsc(ref).size(), "replay back-fills the journal");

        capture.capture(ref, 7L, 1L, DATE, new BigDecimal("2.0000"), BigDecimal.ZERO,
                new BigDecimal("500.0000"), "KRW", new BigDecimal("0.7000"));
        assertEquals(4, entries.findByReferenceOrderByIdAsc(ref).size(), "and only once");
    }

    /**
     * Compare money by VALUE, not scale: the ledger column is {@code NUMERIC(20,8)} so a read-back of
     * 500 may carry any trailing-zero scale, and {@code BigDecimal.equals} is scale-sensitive.
     */
    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but got " + actual);
    }

    /** The amount on the single line matching account/currency/side (fails if there is not exactly one). */
    private static BigDecimal amount(List<LedgerEntryEntity> lines, String account, String currency,
                                     EntryType side) {
        List<LedgerEntryEntity> matches = lines.stream()
                .filter(l -> l.getAccount().equals(account))
                .filter(l -> l.getCurrency().equals(currency))
                .filter(l -> l.getEntryType().equals(side.name()))
                .toList();
        assertEquals(1, matches.size(),
                "expected exactly one " + side + " " + account + " " + currency + " line, got " + matches.size());
        return matches.get(0).getAmount();
    }

    /** Debits must equal credits for every currency present — the double-entry invariant. */
    private static void assertBalancedPerCurrency(List<LedgerEntryEntity> lines) {
        Map<String, List<LedgerEntryEntity>> byCcy = lines.stream()
                .collect(Collectors.groupingBy(LedgerEntryEntity::getCurrency));
        for (var e : byCcy.entrySet()) {
            BigDecimal debits = sum(e.getValue(), EntryType.DEBIT);
            BigDecimal credits = sum(e.getValue(), EntryType.CREDIT);
            assertEquals(0, debits.compareTo(credits),
                    "journal must balance in " + e.getKey() + ": debits=" + debits + " credits=" + credits);
        }
    }

    private static BigDecimal sum(List<LedgerEntryEntity> lines, EntryType side) {
        return lines.stream()
                .filter(l -> l.getEntryType().equals(side.name()))
                .map(LedgerEntryEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
