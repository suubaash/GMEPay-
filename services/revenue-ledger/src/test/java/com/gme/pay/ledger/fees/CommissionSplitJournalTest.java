package com.gme.pay.ledger.fees;

import com.gme.pay.ledger.domain.ledger.JournalStore;
import com.gme.pay.ledger.domain.ledger.LedgerPostingService;
import com.gme.pay.ledger.domain.ledger.RevenueReversalService;
import com.gme.pay.ledger.domain.model.EntryType;
import com.gme.pay.ledger.outbox.OutboxWriter;
import com.gme.pay.ledger.persistence.JournalEntityRepository;
import com.gme.pay.ledger.persistence.JpaJournalStore;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * T2-4: the commission split is no longer record-only (CFO#4) — recording a split now also posts the
 * balanced SCHEME-side journal in the same transaction, from the amounts stored on the record.
 *
 * <p><b>T2-10</b>: the PARTNER-side leg ({@code partner_share_krw}) is now journalled too, as
 * {@code DEBIT EXPENSE_PARTNER_COMMISSION / CREDIT PAYABLE_PARTNER} — a cost GME pays the partner out of
 * commission GME itself earned (GME bills the merchant for the whole fee; the carve is a fraction of GME's
 * own cut). These tests pin the exact amounts of the worked example, the independence of the two legs'
 * idempotency, and that a reversal unwinds the carve to zero.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({JpaJournalStore.class, LedgerPostingService.class, SchemeFeeSplitCalculator.class,
        CommissionSplitCalculator.class, OutboxWriter.class, CommissionSplitRecordService.class})
class CommissionSplitJournalTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 15);

    @Autowired
    private CommissionSplitRecordService service;

    @Autowired
    private LedgerPostingService posting;

    @Autowired
    private LedgerEntryEntityRepository entries;

    @Autowired
    private JournalEntityRepository journals;

    @Autowired
    private JournalStore journalStore;

    @Test
    void recordingASplit_postsTheBalancedSchemeLegJournal() {
        // payout=100,000 KRW, merchant=2.00%, van=0.20%, gmeShare=70%, partnerShare=30%
        // → gross=2000, van=200, net=1800, gmeGross=1260, scheme=540, partner=378, gmeNet=882
        String ref = "CS-JRNL-1";
        service.recordIfAbsent(ref, 7L, 1L, DATE, 100_000L,
                new BigDecimal("0.0200"), new BigDecimal("0.0020"),
                new BigDecimal("0.70"), new BigDecimal("0.30"));

        List<LedgerEntryEntity> lines = entries.findByReferenceOrderByIdAsc(ref);
        assertEquals(5, lines.size(),
                "scheme leg (net DR + GME share CR + scheme share CR) + partner leg (expense DR + payable CR)");

        assertAmount("1800", amount(lines, "RECEIVABLE_PARTNER", EntryType.DEBIT));
        assertAmount("1260", amount(lines, "REVENUE_GME_FEE_SHARE", EntryType.CREDIT));
        assertAmount("540", amount(lines, "PAYABLE_SCHEME", EntryType.CREDIT));

        // Balanced in KRW across both journals: 1800 + 378 DR == 1260 + 540 + 378 CR.
        assertEquals(0, sum(lines, EntryType.DEBIT).compareTo(sum(lines, EntryType.CREDIT)),
                "commission-split journals must balance in KRW");
        assertTrue(lines.stream().allMatch(l -> "KRW".equals(l.getCurrency())));
    }

    /**
     * T2-10 — the owner's rule applied to the established money flow: GME collects the whole merchant fee
     * and the carve is a fraction of GME's OWN cut, so it is a payout cost, booked as expense + payable.
     * Gross revenue is unaffected; retained commission is gross minus this expense = gmeNet (882).
     */
    @Test
    void partnerCarveIsBookedAsCommissionExpenseAndAPayable() {
        String ref = "CS-JRNL-PARTNER";
        var result = service.recordIfAbsent(ref, 7L, 1L, DATE, 100_000L,
                new BigDecimal("0.0200"), new BigDecimal("0.0020"),
                new BigDecimal("0.70"), new BigDecimal("0.30"));

        assertEquals(378L, result.record().getPartnerShareKrw(), "the carve IS recorded");

        List<LedgerEntryEntity> lines = entries.findByReferenceOrderByIdAsc(ref);
        List<String> knownAccounts = List.of("RECEIVABLE_PARTNER", "PAYABLE_SCHEME",
                "REVENUE_GME_FEE_SHARE", "REVENUE_FX_MARGIN", "REVENUE_SERVICE_CHARGE",
                "EXPENSE_PARTNER_COMMISSION", "PAYABLE_PARTNER");
        assertTrue(lines.stream().allMatch(l -> knownAccounts.contains(l.getAccount())),
                "no account code outside the chart of accounts may be posted: "
                        + lines.stream().map(LedgerEntryEntity::getAccount).toList());

        assertAmount("378", amount(lines, "EXPENSE_PARTNER_COMMISSION", EntryType.DEBIT));
        assertAmount("378", amount(lines, "PAYABLE_PARTNER", EntryType.CREDIT));

        // The carve is its OWN balanced journal, not extra lines bolted onto the scheme leg.
        List<LedgerEntryEntity> carve = lines.stream()
                .filter(l -> l.getAccount().startsWith("EXPENSE_") || "PAYABLE_PARTNER".equals(l.getAccount()))
                .toList();
        assertEquals(1, carve.stream().map(LedgerEntryEntity::getJournalId).distinct().count(),
                "both carve lines belong to one journal");
        assertEquals(2, journals.findByReferenceOrderByPostedAtAsc(ref).size(),
                "scheme leg and partner leg are two independently idempotent journals");

        // Revenue is NOT reduced: gross stays 1260 and retained = 1260 - 378 = 882 = gmeNetShareKrw.
        assertAmount("1260", amount(lines, "REVENUE_GME_FEE_SHARE", EntryType.CREDIT));
        assertEquals(882L, result.record().getGmeNetShareKrw());
        assertEquals(0, amount(lines, "REVENUE_GME_FEE_SHARE", EntryType.CREDIT)
                        .subtract(amount(lines, "EXPENSE_PARTNER_COMMISSION", EntryType.DEBIT))
                        .compareTo(new BigDecimal("882")),
                "gross revenue less the commission expense equals GME's retained commission");
    }

    @Test
    void zeroPartnerShare_postsNoCarveJournal() {
        String ref = "CS-JRNL-NO-CARVE";
        // partnerShare = 0 → GME keeps its whole cut; there is no cost and nothing to book.
        service.recordIfAbsent(ref, 7L, 1L, DATE, 100_000L,
                new BigDecimal("0.0200"), new BigDecimal("0.0020"),
                new BigDecimal("0.70"), BigDecimal.ZERO);

        List<LedgerEntryEntity> lines = entries.findByReferenceOrderByIdAsc(ref);
        assertEquals(3, lines.size(), "scheme leg only");
        assertFalse(lines.stream().anyMatch(l -> "PAYABLE_PARTNER".equals(l.getAccount())),
                "a zero carve must not post a nominal zero journal");
    }

    /**
     * The carve journal must back-fill for a split journalled before T2-10 — whose
     * {@code REVENUE_GME_FEE_SHARE} credit already satisfies the SCHEME leg's idempotency probe, so a
     * single shared probe would have left those rows permanently unbooked.
     */
    @Test
    void carveBackFillsForASplitThatAlreadyHasOnlyTheSchemeLeg() {
        String ref = "CS-JRNL-BACKFILL";
        // Simulate a pre-T2-10 row: scheme leg posted directly, no carve.
        posting.postCommissionSplitJournal(ref, 1800L, 1260L, 540L);
        assertEquals(3, entries.findByReferenceOrderByIdAsc(ref).size());

        posting.postPartnerCommissionCarveJournal(ref, 378L);

        List<LedgerEntryEntity> lines = entries.findByReferenceOrderByIdAsc(ref);
        assertEquals(5, lines.size(), "the carve was back-filled");
        assertAmount("378", amount(lines, "PAYABLE_PARTNER", EntryType.CREDIT));
        // And only once.
        assertTrue(posting.postPartnerCommissionCarveJournal(ref, 378L).isEmpty(),
                "a second carve post is an idempotent no-op");
        assertEquals(5, entries.findByReferenceOrderByIdAsc(ref).size());
    }

    @Test
    void aNegativeCarveIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> posting.postPartnerCommissionCarveJournal("CS-JRNL-NEG", -1L));
        assertTrue(e.getMessage().contains("partnerShareKrw must be >= 0"), e.getMessage());
        assertTrue(entries.findByReferenceOrderByIdAsc("CS-JRNL-NEG").isEmpty(), "nothing may be posted");
    }

    @Test
    void replayedSplit_doesNotDoubleBook() {
        String ref = "CS-JRNL-REPLAY";
        service.recordIfAbsent(ref, 7L, 1L, DATE, 100_000L,
                new BigDecimal("0.0200"), new BigDecimal("0.0020"),
                new BigDecimal("0.70"), new BigDecimal("0.30"));
        int linesAfterFirst = entries.findByReferenceOrderByIdAsc(ref).size();

        for (int i = 0; i < 3; i++) {
            var replay = service.recordIfAbsent(ref, 7L, 1L, DATE, 999_999L,
                    new BigDecimal("0.0500"), new BigDecimal("0.0010"),
                    new BigDecimal("0.50"), new BigDecimal("0.50"));
            assertFalse(replay.created(), "replay must not insert a second split row");
        }

        assertEquals(linesAfterFirst, entries.findByReferenceOrderByIdAsc(ref).size(),
                "replays must not add ledger lines");
        assertEquals(2, journals.findByReferenceOrderByPostedAtAsc(ref).size(),
                "replays must not add journals beyond the scheme leg + partner leg");
    }

    /**
     * T2-10 × the existing reversal path: {@code RevenueReversalService} mirrors every non-rounding line,
     * so the carve is unwound with everything else and nets to zero on both new accounts. Neither carve
     * line is a {@code REVENUE_*} DEBIT, so it does not trip that service's already-reversed probe.
     */
    @Test
    void reversalUnwindsTheCarveToZero() {
        String ref = "CS-JRNL-REVERSE";
        service.recordIfAbsent(ref, 7L, 1L, DATE, 100_000L,
                new BigDecimal("0.0200"), new BigDecimal("0.0020"),
                new BigDecimal("0.70"), new BigDecimal("0.30"));

        assertTrue(new RevenueReversalService(journalStore).reverseCapture(ref).isPresent());

        List<LedgerEntryEntity> lines = entries.findByReferenceOrderByIdAsc(ref);
        assertEquals(0, netByAccount(lines, "EXPENSE_PARTNER_COMMISSION").signum(),
                "the commission expense nets to zero after reversal");
        assertEquals(0, netByAccount(lines, "PAYABLE_PARTNER").signum(),
                "the partner payable nets to zero after reversal");
        assertEquals(0, netByAccount(lines, "REVENUE_GME_FEE_SHARE").signum(),
                "and the pre-existing revenue reversal is unaffected");
        assertEquals(0, sum(lines, EntryType.DEBIT).compareTo(sum(lines, EntryType.CREDIT)),
                "the whole reference still balances");

        assertTrue(new RevenueReversalService(journalStore).reverseCapture(ref).isEmpty(),
                "reversal stays idempotent");
    }

    /** DEBIT total minus CREDIT total on one account for the reference. */
    private static BigDecimal netByAccount(List<LedgerEntryEntity> lines, String account) {
        return lines.stream()
                .filter(l -> account.equals(l.getAccount()))
                .map(l -> EntryType.DEBIT.name().equals(l.getEntryType()) ? l.getAmount() : l.getAmount().negate())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void zeroNetFee_postsNothing() {
        String ref = "CS-JRNL-ZERO";
        // merchant rate 0 and van 0 → gross 0, net 0: nothing to journal.
        service.recordIfAbsent(ref, 7L, 1L, DATE, 100_000L,
                BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.70"), new BigDecimal("0.30"));

        assertTrue(entries.findByReferenceOrderByIdAsc(ref).isEmpty(),
                "a zero net merchant fee must not post a nominal zero journal");
    }

    @Test
    void anUnbalancedSplitIsRefused_neverSilentlyFixed() {
        // gmeGross + scheme != net — a corrupt/miscomputed split must fail loudly rather than be
        // massaged into balance (which would hide real money).
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> posting.postCommissionSplitJournal("CS-JRNL-BAD", 1800L, 1260L, 500L));
        assertTrue(e.getMessage().contains("does not conserve KRW"), e.getMessage());
        assertTrue(entries.findByReferenceOrderByIdAsc("CS-JRNL-BAD").isEmpty(), "nothing may be posted");
    }

    /** Compare money by VALUE, not scale — the ledger column is {@code NUMERIC(20,8)}. */
    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but got " + actual);
    }

    private static BigDecimal amount(List<LedgerEntryEntity> lines, String account, EntryType side) {
        List<LedgerEntryEntity> matches = lines.stream()
                .filter(l -> l.getAccount().equals(account))
                .filter(l -> l.getEntryType().equals(side.name()))
                .toList();
        assertEquals(1, matches.size(), "expected exactly one " + side + " " + account + " line");
        return matches.get(0).getAmount();
    }

    private static BigDecimal sum(List<LedgerEntryEntity> lines, EntryType side) {
        return lines.stream()
                .filter(l -> l.getEntryType().equals(side.name()))
                .map(LedgerEntryEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
