package com.gme.pay.ledger.persistence;

import com.gme.pay.ledger.domain.ledger.LedgerPostingService;
import com.gme.pay.ledger.domain.model.EntryType;
import com.gme.pay.ledger.domain.model.Journal;
import com.gme.pay.ledger.domain.model.LedgerEntry;
import com.gme.pay.ledger.fees.SchemeFeeSplitCalculator;
import com.gme.pay.ledger.outbox.OutboxWriter;
import com.gme.pay.ledger.web.TrialBalanceView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * T2-4 trial balance: proves debits equal credits per account and per currency over a date range, and
 * that a real imbalance is reported explicitly rather than absorbed.
 *
 * <p>The imbalance case writes an unmatched {@code ledger_entries} row DIRECTLY through the repository —
 * bypassing {@link Journal#post}, which refuses to build an unbalanced journal. That is exactly what a
 * genuine defect would look like (a direct DB write, a half-applied insert, corruption), and it is the
 * only way the report can ever fire, so it is the case worth testing.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({JpaJournalStore.class, LedgerPostingService.class, SchemeFeeSplitCalculator.class,
        OutboxWriter.class, TrialBalanceService.class})
class TrialBalanceServiceTest {

    /** Fixture period: a fixed past window so "now" postings never leak into it. */
    private static final LocalDate PERIOD_START = LocalDate.of(2026, 5, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 5, 31);
    private static final Instant IN_PERIOD = LocalDate.of(2026, 5, 15)
            .atStartOfDay(ZoneOffset.UTC).toInstant();
    private static final Instant OUT_OF_PERIOD = LocalDate.of(2026, 4, 15)
            .atStartOfDay(ZoneOffset.UTC).toInstant();

    @Autowired
    private JpaJournalStore store;

    @Autowired
    private TrialBalanceService trialBalance;

    @Autowired
    private LedgerPostingService posting;

    @Autowired
    private JournalEntityRepository journals;

    @Autowired
    private LedgerEntryEntityRepository entries;

    @Test
    void fixturePeriod_debitsEqualCreditsInEveryCurrency() {
        // A realistic period: a cross-border capture (USD margin + KRW service charge), a commission
        // split (KRW), a rounding residual (USD) and a reversal (USD).
        seedCapture("TB-TXN-1", new BigDecimal("1.5000"), new BigDecimal("500"), "KRW");
        seedCapture("TB-TXN-2", new BigDecimal("2.2500"), new BigDecimal("500"), "KRW");
        seedSplit("TB-TXN-1-SPLIT", 1800L, 1260L, 540L);
        // T2-10: the partner commission carve on that same split (378 of the 1260 GME earned).
        seedCarve("TB-TXN-1-SPLIT", 378L);
        seedTwoLine("TB-TXN-3", "RECEIVABLE_PARTNER", "REVENUE_ROUNDING", new BigDecimal("0.0070"), "USD");
        seedTwoLine("TB-TXN-4", "REVENUE_REVERSAL", "RECEIVABLE_PARTNER", new BigDecimal("125.50"), "USD");

        TrialBalanceView view = trialBalance.compute(PERIOD_START, PERIOD_END);

        assertTrue(view.balanced(), "the whole period must balance: " + view.imbalances());
        assertTrue(view.imbalances().isEmpty());
        assertEquals(2, view.currencies().size(), "USD and KRW both in scope");
        for (TrialBalanceView.CurrencyTotals c : view.currencies()) {
            assertEquals(0, c.difference().signum(),
                    c.currency() + " debits(" + c.debitTotal() + ") != credits(" + c.creditTotal() + ")");
            assertTrue(c.balanced());
        }

        // Per-account rows are present and carry the movement, not zero.
        TrialBalanceView.Row fxMargin = row(view, "REVENUE_FX_MARGIN", "USD");
        assertEquals(0, fxMargin.creditTotal().compareTo(new BigDecimal("3.75")),
                "1.5000 + 2.2500 credited to FX margin, got " + fxMargin.creditTotal());
        TrialBalanceView.Row gmeShare = row(view, "REVENUE_GME_FEE_SHARE", "KRW");
        assertEquals(0, gmeShare.creditTotal().compareTo(new BigDecimal("1260")));

        // T2-10: the carve appears on its own two accounts and does NOT reduce the revenue row above.
        assertEquals(0, row(view, "EXPENSE_PARTNER_COMMISSION", "KRW").debitTotal()
                        .compareTo(new BigDecimal("378")));
        assertEquals(0, row(view, "PAYABLE_PARTNER", "KRW").creditTotal()
                        .compareTo(new BigDecimal("378")));
    }

    @Test
    void aDeliberateImbalanceIsReportedExplicitly() {
        seedCapture("TB-IMB-1", new BigDecimal("10.0000"), BigDecimal.ZERO, "USD");
        assertTrue(trialBalance.compute(PERIOD_START, PERIOD_END).balanced(), "precondition: balanced");

        // Corrupt the book: one orphan DEBIT with no matching credit, on an in-period journal head.
        String journalId = "tb-orphan-journal";
        journals.save(new JournalEntity(journalId, "TB-IMB-ORPHAN", IN_PERIOD));
        entries.save(new LedgerEntryEntity(journalId, "RECEIVABLE_PARTNER",
                new BigDecimal("7.25000000"), "USD", "DEBIT", "TB-IMB-ORPHAN"));

        TrialBalanceView view = trialBalance.compute(PERIOD_START, PERIOD_END);

        assertFalse(view.balanced(), "an orphan debit must break the trial balance");
        assertEquals(1, view.imbalances().size(), "only USD is off");
        TrialBalanceView.CurrencyTotals usd = view.imbalances().get(0);
        assertEquals("USD", usd.currency());
        assertEquals(0, usd.difference().compareTo(new BigDecimal("7.25")),
                "the reported difference must be the exact orphan amount, got " + usd.difference());
        assertFalse(usd.balanced());
    }

    @Test
    void journalsOutsideTheRangeAreExcluded() {
        seedTwoLineAt("TB-OLD", OUT_OF_PERIOD, "RECEIVABLE_PARTNER", "REVENUE_FX_MARGIN",
                new BigDecimal("99.00"), "USD");

        TrialBalanceView view = trialBalance.compute(PERIOD_START, PERIOD_END);
        assertTrue(view.rows().isEmpty(), "a journal posted before the range must not appear");
        assertTrue(view.balanced(), "an empty period trivially balances");

        // Widen the range to include it — now it is in scope and still balances.
        TrialBalanceView wider = trialBalance.compute(LocalDate.of(2026, 4, 1), PERIOD_END);
        assertFalse(wider.rows().isEmpty());
        assertTrue(wider.balanced());
    }

    @Test
    void invertedRangeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> trialBalance.compute(PERIOD_END, PERIOD_START));
    }

    // ---- fixtures: post through the real posting service, then restamp into the fixture period ----

    /**
     * Post a capture journal and move it into the fixture period. The posting service stamps
     * {@code Instant.now()}, so the journal is re-saved under a period-dated head — the ledger LINES are
     * what the report aggregates and they are unchanged.
     */
    private void seedCapture(String ref, BigDecimal fxMarginUsd, BigDecimal serviceCharge, String ccy) {
        Optional<Journal> posted = posting.postCapturedRevenueJournal(ref, fxMarginUsd, serviceCharge, ccy);
        assertTrue(posted.isPresent(), "fixture must post a journal");
        restamp(posted.get());
    }

    private void seedSplit(String ref, long net, long gmeGross, long scheme) {
        Optional<Journal> posted = posting.postCommissionSplitJournal(ref, net, gmeGross, scheme);
        assertTrue(posted.isPresent());
        restamp(posted.get());
    }

    private void seedCarve(String ref, long partnerShareKrw) {
        Optional<Journal> posted = posting.postPartnerCommissionCarveJournal(ref, partnerShareKrw);
        assertTrue(posted.isPresent());
        restamp(posted.get());
    }

    private void seedTwoLine(String ref, String debitAccount, String creditAccount,
                             BigDecimal amount, String ccy) {
        seedTwoLineAt(ref, IN_PERIOD, debitAccount, creditAccount, amount, ccy);
    }

    private void seedTwoLineAt(String ref, Instant postedAt, String debitAccount, String creditAccount,
                               BigDecimal amount, String ccy) {
        store.save(Journal.rehydrate("tb-" + ref, postedAt, List.of(
                new LedgerEntry(debitAccount, amount, ccy, EntryType.DEBIT, ref),
                new LedgerEntry(creditAccount, amount, ccy, EntryType.CREDIT, ref))));
    }

    /** Move an already-posted journal's head into the fixture period (lines follow via journal_id). */
    private void restamp(Journal journal) {
        JournalEntity head = journals.findById(journal.journalId()).orElseThrow();
        head.setPostedAt(IN_PERIOD);
        journals.save(head);
    }

    private static TrialBalanceView.Row row(TrialBalanceView view, String account, String currency) {
        return view.rows().stream()
                .filter(r -> r.account().equals(account) && r.currency().equals(currency))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no trial-balance row for " + account + "/" + currency
                        + " in " + view.rows()));
    }
}
