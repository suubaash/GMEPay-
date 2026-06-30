package com.gme.pay.ledger.persistence;

import com.gme.pay.ledger.domain.ledger.LedgerPostingService;
import com.gme.pay.ledger.fees.SchemeFeeSplitCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T27: the {@code REVENUE_ROUNDING} aggregate exposed via {@link InMemoryJournalStore#sumRoundingByDateRange}
 * must reconcile to the signed sum of the rounding residuals posted in the period (gains add, losses
 * subtract). Uses the real {@link LedgerPostingService} so the account codes and DEBIT/CREDIT
 * direction are exercised end-to-end rather than hand-rolled.
 */
class RoundingAggregationTest {

    private InMemoryJournalStore store;
    private LedgerPostingService posting;

    @BeforeEach
    void setUp() {
        store = new InMemoryJournalStore();
        posting = new LedgerPostingService(store, new SchemeFeeSplitCalculator());
    }

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @Test
    void aggregateReconcilesToSignedSumOfResiduals() {
        posting.postRoundingResidual("TXN-1", new BigDecimal("0.007"), "USD");   // gain +0.007
        posting.postRoundingResidual("TXN-2", new BigDecimal("-0.003"), "USD");  // loss -0.003
        posting.postRoundingResidual("TXN-3", new BigDecimal("0.010"), "USD");   // gain +0.010

        BigDecimal total = store.sumRoundingByDateRange(TODAY, TODAY, "USD");

        // 0.007 - 0.003 + 0.010 = 0.014
        assertThat(total).isEqualByComparingTo("0.014");
    }

    @Test
    void onlyMatchingCurrencyIsSummed() {
        posting.postRoundingResidual("TXN-1", new BigDecimal("0.007"), "USD");
        posting.postRoundingResidual("TXN-2", new BigDecimal("0.42"), "KRW");

        assertThat(store.sumRoundingByDateRange(TODAY, TODAY, "USD")).isEqualByComparingTo("0.007");
        assertThat(store.sumRoundingByDateRange(TODAY, TODAY, "KRW")).isEqualByComparingTo("0.42");
    }

    @Test
    void outOfRangeDatesAreExcluded() {
        posting.postRoundingResidual("TXN-1", new BigDecimal("0.007"), "USD"); // posted today

        // A window entirely in the past must not pick up today's journal.
        assertThat(store.sumRoundingByDateRange(TODAY.minusDays(10), TODAY.minusDays(1), "USD"))
                .isEqualByComparingTo("0");
    }

    @Test
    void noRoundingJournals_returnsZeroNotNull() {
        assertThat(store.sumRoundingByDateRange(TODAY, TODAY, "USD")).isEqualByComparingTo("0");
    }
}
