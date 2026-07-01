package com.gme.pay.ledger.domain.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gme.pay.ledger.domain.model.EntryType;
import com.gme.pay.ledger.domain.model.Journal;
import com.gme.pay.ledger.domain.model.LedgerEntry;
import com.gme.pay.ledger.fees.SchemeFeeSplitCalculator;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unit tests for posting the per-partner rounding residual to the rounding ledger. */
class RoundingResidualTest {

    private static final String ACC_ROUNDING = "REVENUE_ROUNDING";

    /** Minimal in-memory JournalStore (revenue-ledger owns its DB; tests stub the port). */
    private static final class InMemoryStore implements JournalStore {
        final List<Journal> saved = new ArrayList<>();
        public Journal save(Journal j) { saved.add(j); return j; }
        public Optional<Journal> findById(String id) {
            return saved.stream().filter(j -> j.journalId().equals(id)).findFirst();
        }
        public List<Journal> findByReference(String ref) { return saved; }
        public Optional<Journal> findRoundingResidualByReference(String ref) {
            return saved.stream()
                    .filter(j -> j.entries().stream().anyMatch(e ->
                            ref.equals(e.reference()) && ACC_ROUNDING.equals(e.account())))
                    .findFirst();
        }
        public BigDecimal sumRoundingByDateRange(java.time.LocalDate s, java.time.LocalDate e, String ccy) {
            return BigDecimal.ZERO; // not exercised here; see RoundingAggregationTest
        }
    }

    private LedgerPostingService service() {
        return new LedgerPostingService(new InMemoryStore(), new SchemeFeeSplitCalculator());
    }

    private BigDecimal amountOn(Journal j, String account, EntryType type) {
        return j.entries().stream()
                .filter(e -> e.account().equals(account) && e.type() == type)
                .map(LedgerEntry::amount).findFirst().orElse(null);
    }

    @Test
    void positiveResidual_postsRoundingGainCredit() {
        Journal j = service().postRoundingResidual("TXN-1", new BigDecimal("0.007"), "USD");
        // gain: REVENUE_ROUNDING credited, RECEIVABLE_PARTNER debited, balanced
        assertEquals(0, amountOn(j, "REVENUE_ROUNDING", EntryType.CREDIT).compareTo(new BigDecimal("0.007")));
        assertEquals(0, amountOn(j, "RECEIVABLE_PARTNER", EntryType.DEBIT).compareTo(new BigDecimal("0.007")));
    }

    @Test
    void negativeResidual_postsRoundingLossDebit() {
        Journal j = service().postRoundingResidual("TXN-2", new BigDecimal("-0.003"), "USD");
        // loss: REVENUE_ROUNDING debited |residual|
        assertEquals(0, amountOn(j, "REVENUE_ROUNDING", EntryType.DEBIT).compareTo(new BigDecimal("0.003")));
        assertEquals(0, amountOn(j, "RECEIVABLE_PARTNER", EntryType.CREDIT).compareTo(new BigDecimal("0.003")));
    }

    @Test
    void zeroResidual_postsNothing() {
        assertNull(service().postRoundingResidual("TXN-3", BigDecimal.ZERO, "USD"));
    }

    @Test
    void batchIdReference_postsAndIsAuditedVerbatim_settlementReconIR2() {
        // settlement-reconciliation posts its per-batch aggregate residual keyed by the batch id
        // ("ZP00NN-YYYYMMDD-WINDOW"), not a per-txn ref. The reference is opaque and written verbatim
        // onto every ledger line so the residual ties back to the batch that produced it.
        String batchId = "ZP0061-20260630-AM";
        Journal j = service().postRoundingResidual(batchId, new BigDecimal("0.050"), "USD");
        assertTrue(j.entries().stream().allMatch(e -> batchId.equals(e.reference())));
        assertEquals(0, amountOn(j, "REVENUE_ROUNDING", EntryType.CREDIT).compareTo(new BigDecimal("0.050")));
    }

    @Test
    void repeatPostSameReference_isIdempotent_returnsExistingJournal() {
        LedgerPostingService svc = service();
        String batchId = "ZP0061-20260630-AM";
        Journal first = svc.postRoundingResidual(batchId, new BigDecimal("0.050"), "USD");
        Journal second = svc.postRoundingResidual(batchId, new BigDecimal("0.050"), "USD");
        // Same id back; the service short-circuits on the pre-existing rounding journal.
        assertEquals(first.journalId(), second.journalId());
    }

    @Test
    void residualJournalIsBalanced() {
        Journal j = service().postRoundingResidual("TXN-4", new BigDecimal("0.42"), "KRW");
        BigDecimal debits = j.entries().stream().filter(e -> e.type() == EntryType.DEBIT)
                .map(LedgerEntry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credits = j.entries().stream().filter(e -> e.type() == EntryType.CREDIT)
                .map(LedgerEntry::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertTrue(debits.compareTo(credits) == 0);
    }
}
