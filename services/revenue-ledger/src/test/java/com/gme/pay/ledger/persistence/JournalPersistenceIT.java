package com.gme.pay.ledger.persistence;

import com.gme.pay.ledger.domain.ledger.LedgerPostingService;
import com.gme.pay.ledger.domain.model.EntryType;
import com.gme.pay.ledger.domain.model.Journal;
import com.gme.pay.ledger.fees.SchemeFeeSplitCalculator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Integration test for the JPA persistence layer of the revenue ledger.
 *
 * <p>Uses {@code @DataJpaTest} with the application's configured H2 datasource
 * (Flyway runs the {@code V001} and {@code V002} migrations) — no Docker,
 * no Testcontainers.
 *
 * <p>Verifies that:
 * <ol>
 *   <li>{@link LedgerPostingService#postRevenueCapture(String, BigDecimal, BigDecimal, String)}
 *       writes balanced entries (debits == credits per currency) via {@link JpaJournalStore}.</li>
 *   <li>{@link LedgerPostingService#postRoundingResidual(String, BigDecimal, String)}
 *       writes a balanced gain/loss journal against the {@code REVENUE_ROUNDING} account.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@Import({JpaJournalStore.class, InMemoryJournalStore.class, LedgerPostingService.class, SchemeFeeSplitCalculator.class})
class JournalPersistenceIT {

    @Autowired
    private JpaJournalStore jpaJournalStore;

    @Autowired
    private LedgerPostingService ledgerPostingService;

    @Autowired
    private JournalEntityRepository journalRepo;

    @Autowired
    private LedgerEntryEntityRepository entryRepo;

    @Test
    void postRevenueCapture_persistsBalancedEntries() {
        // Use a unique reference per test run so we can isolate the lines we just wrote.
        String ref = "TXN-IT-CAP-" + UUID.randomUUID();

        BigDecimal fxMargin     = new BigDecimal("12.3400");
        BigDecimal serviceCharge = new BigDecimal("500");

        Journal journal = ledgerPostingService.postRevenueCapture(ref, fxMargin, serviceCharge, "KRW");

        // 1. journal row exists with the minted id.
        assertTrue(journalRepo.existsById(journal.journalId()),
                "journal row should be written");

        // 2. ledger_entries rows exist for this reference (USD pair + KRW pair = 4 rows).
        List<LedgerEntryEntity> lines = entryRepo.findByReferenceOrderByIdAsc(ref);
        assertEquals(4, lines.size(),
                "should have 2 USD lines (fx margin) + 2 KRW lines (service charge)");

        // 3. lines balance per currency: sum of DEBITs == sum of CREDITs for each currency.
        assertBalancedPerCurrency(lines);

        // 4. one of the credit lines hits REVENUE_FX_MARGIN (USD) and one hits REVENUE_SERVICE_CHARGE (KRW).
        assertTrue(lines.stream().anyMatch(l ->
                ChartOfAccounts.REVENUE_FX_MARGIN.equals(l.getAccount())
                        && "USD".equals(l.getCurrency())
                        && "CREDIT".equals(l.getEntryType())),
                "FX margin must be credited to REVENUE_FX_MARGIN");
        assertTrue(lines.stream().anyMatch(l ->
                ChartOfAccounts.REVENUE_SERVICE_CHARGE.equals(l.getAccount())
                        && "KRW".equals(l.getCurrency())
                        && "CREDIT".equals(l.getEntryType())),
                "service charge must be credited to REVENUE_SERVICE_CHARGE");
    }

    @Test
    void postRoundingResidual_positive_postsBalancedGainJournalToRevenueRounding() {
        String ref = "TXN-IT-ROUND-GAIN-" + UUID.randomUUID();

        // residual = +0.007 (partner booked less than precise) -> rounding GAIN
        Journal journal = ledgerPostingService.postRoundingResidual(ref, new BigDecimal("0.007"), "USD");
        assertTrue(journalRepo.existsById(journal.journalId()),
                "rounding-gain journal row should be written");

        List<LedgerEntryEntity> lines = entryRepo.findByReferenceOrderByIdAsc(ref);
        assertEquals(2, lines.size(), "rounding-gain journal has exactly 2 lines");

        // REVENUE_ROUNDING is credited (gain), RECEIVABLE_PARTNER is debited, both = 0.007 USD.
        assertEquals(1, lines.stream().filter(l ->
                ChartOfAccounts.REVENUE_ROUNDING.equals(l.getAccount())
                        && "CREDIT".equals(l.getEntryType())
                        && "USD".equals(l.getCurrency())
                        && l.getAmount().compareTo(new BigDecimal("0.007")) == 0).count(),
                "REVENUE_ROUNDING credit of 0.007 USD expected");

        assertEquals(1, lines.stream().filter(l ->
                ChartOfAccounts.RECEIVABLE_PARTNER.equals(l.getAccount())
                        && "DEBIT".equals(l.getEntryType())
                        && "USD".equals(l.getCurrency())
                        && l.getAmount().compareTo(new BigDecimal("0.007")) == 0).count(),
                "RECEIVABLE_PARTNER debit of 0.007 USD expected");

        assertBalancedPerCurrency(lines);
    }

    @Test
    void postRoundingResidual_negative_postsBalancedLossJournalToRevenueRounding() {
        String ref = "TXN-IT-ROUND-LOSS-" + UUID.randomUUID();

        // residual = -0.003 (partner booked more than precise) -> rounding LOSS
        Journal journal = ledgerPostingService.postRoundingResidual(ref, new BigDecimal("-0.003"), "USD");
        assertTrue(journalRepo.existsById(journal.journalId()),
                "rounding-loss journal row should be written");

        List<LedgerEntryEntity> lines = entryRepo.findByReferenceOrderByIdAsc(ref);
        assertEquals(2, lines.size(), "rounding-loss journal has exactly 2 lines");

        // REVENUE_ROUNDING is debited (loss), RECEIVABLE_PARTNER is credited, both = 0.003 USD.
        assertEquals(1, lines.stream().filter(l ->
                ChartOfAccounts.REVENUE_ROUNDING.equals(l.getAccount())
                        && "DEBIT".equals(l.getEntryType())
                        && l.getAmount().compareTo(new BigDecimal("0.003")) == 0).count(),
                "REVENUE_ROUNDING debit of 0.003 USD expected");

        assertEquals(1, lines.stream().filter(l ->
                ChartOfAccounts.RECEIVABLE_PARTNER.equals(l.getAccount())
                        && "CREDIT".equals(l.getEntryType())
                        && l.getAmount().compareTo(new BigDecimal("0.003")) == 0).count(),
                "RECEIVABLE_PARTNER credit of 0.003 USD expected");

        assertBalancedPerCurrency(lines);
    }

    @Test
    void jpaJournalStore_findById_returnsStoredJournalWithPreservedId() {
        String ref = "TXN-IT-FIND-" + UUID.randomUUID();
        Journal saved = ledgerPostingService.postRoundingResidual(ref, new BigDecimal("0.42"), "KRW");

        var found = jpaJournalStore.findById(saved.journalId());
        assertTrue(found.isPresent(), "journal must be retrievable by its stored id");
        assertEquals(saved.journalId(), found.get().journalId(),
                "stored journalId must round-trip unchanged");
        assertEquals(2, found.get().entries().size());
    }

    @Test
    void jpaJournalStore_save_isIdempotentByJournalId() {
        String ref = "TXN-IT-IDEMP-" + UUID.randomUUID();
        Journal first = ledgerPostingService.postRevenueCapture(ref, new BigDecimal("1.0000"), BigDecimal.ZERO, "USD");
        long countAfterFirst = entryRepo.findByReferenceOrderByIdAsc(ref).size();

        // Re-save the same journal instance: existing row check should short-circuit.
        Journal again = jpaJournalStore.save(first);

        assertEquals(first.journalId(), again.journalId());
        long countAfterSecond = entryRepo.findByReferenceOrderByIdAsc(ref).size();
        assertEquals(countAfterFirst, countAfterSecond,
                "saving an already-stored journal must not duplicate entries");
        assertFalse(countAfterSecond == 0, "expected at least one stored line");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Assert sum(DEBIT) == sum(CREDIT) per currency for the given lines.
     * Mirrors {@link Journal}'s in-memory balance check, but applied to the persisted rows.
     */
    private static void assertBalancedPerCurrency(List<LedgerEntryEntity> lines) {
        var currencies = lines.stream().map(LedgerEntryEntity::getCurrency).distinct().toList();
        for (String ccy : currencies) {
            BigDecimal debits = lines.stream()
                    .filter(l -> ccy.equals(l.getCurrency()) && EntryType.DEBIT.name().equals(l.getEntryType()))
                    .map(LedgerEntryEntity::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal credits = lines.stream()
                    .filter(l -> ccy.equals(l.getCurrency()) && EntryType.CREDIT.name().equals(l.getEntryType()))
                    .map(LedgerEntryEntity::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(0, debits.compareTo(credits),
                    "ledger must balance for currency " + ccy
                            + " (debits=" + debits + " credits=" + credits + ")");
        }
    }
}
