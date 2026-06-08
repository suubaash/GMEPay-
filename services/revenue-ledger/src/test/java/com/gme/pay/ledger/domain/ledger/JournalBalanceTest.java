package com.gme.pay.ledger.domain.ledger;

import com.gme.pay.ledger.domain.model.EntryType;
import com.gme.pay.ledger.domain.model.Journal;
import com.gme.pay.ledger.domain.model.LedgerEntry;
import com.gme.pay.ledger.domain.model.UnbalancedJournalException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for double-entry journal balance enforcement.
 * Verifies that {@link Journal#post(List)} accepts balanced journals and rejects unbalanced ones.
 * No Spring context, no Docker — plain JUnit 5.
 */
class JournalBalanceTest {

    // -----------------------------------------------------------------------
    // Happy-path: balanced journals
    // -----------------------------------------------------------------------

    @Test
    void balancedTwoLineJournal_postSucceeds() {
        var entries = List.of(
            entry("RECEIVABLE_PARTNER", "100.00", "USD", EntryType.DEBIT,  "TXN-001"),
            entry("REVENUE_FX_MARGIN",  "100.00", "USD", EntryType.CREDIT, "TXN-001")
        );

        Journal journal = Journal.post(entries);

        assertNotNull(journal.journalId());
        assertNotNull(journal.postedAt());
        assertEquals(2, journal.entries().size());
    }

    @Test
    void balancedThreeLineJournal_feeShareSplit_postSucceeds() {
        // net=108 KRW split: gme=75, zeropay=33
        var entries = List.of(
            entry("RECEIVABLE_PARTNER", "108", "KRW", EntryType.DEBIT,  "TXN-002"),
            entry("REVENUE_GME_FEE",    "75",  "KRW", EntryType.CREDIT, "TXN-002"),
            entry("PAYABLE_SCHEME",     "33",  "KRW", EntryType.CREDIT, "TXN-002")
        );

        Journal journal = Journal.post(entries);

        assertEquals(3, journal.entries().size());
    }

    @Test
    void balancedMultiCurrencyJournal_eachCurrencyBalances_postSucceeds() {
        // USD lines balanced, KRW lines balanced independently
        var entries = List.of(
            entry("RECEIVABLE_PARTNER",  "50.00", "USD", EntryType.DEBIT,  "TXN-003"),
            entry("REVENUE_FX_MARGIN",   "50.00", "USD", EntryType.CREDIT, "TXN-003"),
            entry("RECEIVABLE_PARTNER",  "500",   "KRW", EntryType.DEBIT,  "TXN-003"),
            entry("REVENUE_SVC_CHARGE",  "500",   "KRW", EntryType.CREDIT, "TXN-003")
        );

        assertDoesNotThrow(() -> Journal.post(entries));
    }

    // -----------------------------------------------------------------------
    // Unhappy-path: unbalanced journals
    // -----------------------------------------------------------------------

    @Test
    void unbalancedJournal_debitGreaterThanCredit_throwsUnbalancedJournalException() {
        var entries = List.of(
            entry("RECEIVABLE_PARTNER", "100.00", "USD", EntryType.DEBIT,  "TXN-004"),
            entry("REVENUE_FX_MARGIN",  "99.00",  "USD", EntryType.CREDIT, "TXN-004")
        );

        UnbalancedJournalException ex = assertThrows(UnbalancedJournalException.class,
                () -> Journal.post(entries));

        assertEquals("USD", ex.currency());
        assertEquals(0, ex.debits().compareTo(new BigDecimal("100.00")));
        assertEquals(0, ex.credits().compareTo(new BigDecimal("99.00")));
    }

    @Test
    void unbalancedJournal_creditGreaterThanDebit_throwsUnbalancedJournalException() {
        var entries = List.of(
            entry("RECEIVABLE_PARTNER", "75",  "KRW", EntryType.DEBIT,  "TXN-005"),
            entry("REVENUE_GME_FEE",    "108", "KRW", EntryType.CREDIT, "TXN-005")
        );

        assertThrows(UnbalancedJournalException.class, () -> Journal.post(entries));
    }

    @Test
    void unbalancedJournal_missingCreditLine_throwsUnbalancedJournalException() {
        // Two debits, no credits
        var entries = List.of(
            entry("ACCOUNT_A", "50.00", "USD", EntryType.DEBIT, "TXN-006"),
            entry("ACCOUNT_B", "50.00", "USD", EntryType.DEBIT, "TXN-006")
        );

        assertThrows(UnbalancedJournalException.class, () -> Journal.post(entries));
    }

    @Test
    void journalWithLessThanTwoEntries_throwsIllegalArgument() {
        var entries = List.of(
            entry("ACCOUNT_A", "100.00", "USD", EntryType.DEBIT, "TXN-007")
        );

        assertThrows(IllegalArgumentException.class, () -> Journal.post(entries));
    }

    @Test
    void unbalancedSecondCurrency_throwsEvenIfFirstCurrencyBalances() {
        // USD balanced, KRW unbalanced
        var entries = List.of(
            entry("RECEIVABLE_PARTNER", "50.00", "USD", EntryType.DEBIT,  "TXN-008"),
            entry("REVENUE_FX_MARGIN",  "50.00", "USD", EntryType.CREDIT, "TXN-008"),
            entry("RECEIVABLE_PARTNER", "500",   "KRW", EntryType.DEBIT,  "TXN-008"),
            entry("REVENUE_SVC_CHARGE", "499",   "KRW", EntryType.CREDIT, "TXN-008")  // off by 1
        );

        UnbalancedJournalException ex = assertThrows(UnbalancedJournalException.class,
                () -> Journal.post(entries));
        assertEquals("KRW", ex.currency());
    }

    // -----------------------------------------------------------------------
    // 70/30 split specific: verify gme + zeropay always balances the debit
    // -----------------------------------------------------------------------

    @Test
    void feeShareSplit_seventyThirtyBalances() {
        // Verify a correct 70/30 split posts cleanly: net=108, gme=75, zeropay=33
        long net     = 108L;
        long gme     = 75L;
        long zeropay = 33L;
        assertEquals(net, gme + zeropay, "pre-condition: gme + zeropay must equal net");

        var entries = List.of(
            entry("RECEIVABLE_PARTNER", String.valueOf(net),     "KRW", EntryType.DEBIT,  "TXN-009"),
            entry("REVENUE_GME_FEE",    String.valueOf(gme),     "KRW", EntryType.CREDIT, "TXN-009"),
            entry("PAYABLE_SCHEME",     String.valueOf(zeropay), "KRW", EntryType.CREDIT, "TXN-009")
        );

        assertDoesNotThrow(() -> Journal.post(entries));
    }

    @Test
    void feeShareSplit_incorrectSplit_throwsUnbalanced() {
        // gme=70 + zeropay=29 = 99 != 100 net -> unbalanced
        var entries = List.of(
            entry("RECEIVABLE_PARTNER", "100", "KRW", EntryType.DEBIT,  "TXN-010"),
            entry("REVENUE_GME_FEE",    "70",  "KRW", EntryType.CREDIT, "TXN-010"),
            entry("PAYABLE_SCHEME",     "29",  "KRW", EntryType.CREDIT, "TXN-010")
        );

        assertThrows(UnbalancedJournalException.class, () -> Journal.post(entries));
    }

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static LedgerEntry entry(String account, String amount, String currency,
                                     EntryType type, String reference) {
        return new LedgerEntry(account, new BigDecimal(amount), currency, type, reference);
    }
}
