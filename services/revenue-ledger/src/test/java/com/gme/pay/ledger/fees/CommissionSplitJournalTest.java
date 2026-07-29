package com.gme.pay.ledger.fees;

import com.gme.pay.ledger.domain.ledger.LedgerPostingService;
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
 * <p>Also pins the deliberate boundary of this fix: the PARTNER-side leg ({@code partner_share_krw}) is
 * NOT journalled, because no account code exists for it and inventing one is a finance-owner decision.
 * The test asserts its absence on purpose, so that when the account is decided the test fails and forces
 * the mapping to be added rather than the gap being forgotten.
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

    @Test
    void recordingASplit_postsTheBalancedSchemeLegJournal() {
        // payout=100,000 KRW, merchant=2.00%, van=0.20%, gmeShare=70%, partnerShare=30%
        // → gross=2000, van=200, net=1800, gmeGross=1260, scheme=540, partner=378, gmeNet=882
        String ref = "CS-JRNL-1";
        service.recordIfAbsent(ref, 7L, 1L, DATE, 100_000L,
                new BigDecimal("0.0200"), new BigDecimal("0.0020"),
                new BigDecimal("0.70"), new BigDecimal("0.30"));

        List<LedgerEntryEntity> lines = entries.findByReferenceOrderByIdAsc(ref);
        assertEquals(3, lines.size(), "net receivable debit + GME share credit + scheme share credit");

        assertAmount("1800", amount(lines, "RECEIVABLE_PARTNER", EntryType.DEBIT));
        assertAmount("1260", amount(lines, "REVENUE_GME_FEE_SHARE", EntryType.CREDIT));
        assertAmount("540", amount(lines, "PAYABLE_SCHEME", EntryType.CREDIT));

        // Balanced in KRW: 1800 DR == 1260 + 540 CR.
        assertEquals(0, sum(lines, EntryType.DEBIT).compareTo(sum(lines, EntryType.CREDIT)),
                "commission-split journal must balance in KRW");
        assertTrue(lines.stream().allMatch(l -> "KRW".equals(l.getCurrency())));
    }

    @Test
    void partnerSideLegIsDeliberatelyNotJournalled_awaitingAnAccountCode() {
        String ref = "CS-JRNL-PARTNER";
        var result = service.recordIfAbsent(ref, 7L, 1L, DATE, 100_000L,
                new BigDecimal("0.0200"), new BigDecimal("0.0020"),
                new BigDecimal("0.70"), new BigDecimal("0.30"));

        assertEquals(378L, result.record().getPartnerShareKrw(), "the carve IS recorded");

        List<LedgerEntryEntity> lines = entries.findByReferenceOrderByIdAsc(ref);
        // Only the five account codes this module defines may ever appear.
        List<String> knownAccounts = List.of("RECEIVABLE_PARTNER", "PAYABLE_SCHEME",
                "REVENUE_GME_FEE_SHARE", "REVENUE_FX_MARGIN", "REVENUE_SERVICE_CHARGE");
        assertTrue(lines.stream().allMatch(l -> knownAccounts.contains(l.getAccount())),
                "no invented account code may be posted: " + lines.stream().map(LedgerEntryEntity::getAccount).toList());
        assertFalse(lines.stream().anyMatch(l -> l.getAmount().compareTo(new BigDecimal("378")) == 0),
                "the partner carve must NOT be journalled while it has no account code — it is reported "
                        + "by GET /v1/revenue/journal-reconciliation as an unmapped component instead");
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
        assertEquals(1, journals.findByReferenceOrderByPostedAtAsc(ref).size(),
                "replays must not add journals");
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
