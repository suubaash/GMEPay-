package com.gme.pay.prefunding.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import com.gme.pay.prefunding.persistence.CumulativeUsageLedgerRepository;
import com.gme.pay.prefunding.persistence.LedgerEntryRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-to-end test of the AML cumulative-cap counter ({@link PrefundingService#chargeCumulative} /
 * {@link PrefundingService#reverseCumulative}) against Flyway-managed H2. Covers cumulative aggregation
 * across transactions, cap breach, null-cap = unconstrained, idempotency, and reverse net-zero. The
 * race-closure rests on the same per-partner {@code SELECT...FOR UPDATE} lock proven for reserve/capture
 * (a concurrent Postgres IT mirrors {@code PostgresAtomicDeductionIT} as a follow-up).
 */
@SpringBootTest
@ActiveProfiles("test")
class PrefundingCumulativeUsageIT {

    private static final String PARTNER = "CUM_PARTNER";

    @Autowired private PrefundingService service;
    @Autowired private PartnerBalanceRepository balances;
    @Autowired private LedgerEntryRepository ledger;
    @Autowired private CumulativeUsageLedgerRepository cumulativeLedger;

    @BeforeEach
    void seedPartner() {
        cumulativeLedger.deleteAll();
        ledger.deleteAll();
        balances.deleteAll();
        balances.save(new PartnerBalanceEntity(
                PARTNER, "USD",
                new BigDecimal("1000000.00000000"),
                new BigDecimal("0.00000000"),
                Instant.now()));
    }

    private static BigDecimal usd(String v) {
        return new BigDecimal(v);
    }

    @Test
    @Transactional
    @DisplayName("charge under caps appends + reports usage; cumulative sums across transactions")
    void chargesAggregate() {
        PrefundingService.CumulativeChargeResult r1 = service.chargeCumulative(
                PARTNER, "T1", usd("300"), usd("1000"), null, null);
        assertEquals(0, r1.dailyUsage().compareTo(usd("300")));

        PrefundingService.CumulativeChargeResult r2 = service.chargeCumulative(
                PARTNER, "T2", usd("250"), usd("1000"), null, null);
        assertEquals(0, r2.dailyUsage().compareTo(usd("550")), "daily usage accumulates across txns");

        // Exactly at the cap is allowed (boundary inclusive): 550 + 450 = 1000.
        PrefundingService.CumulativeChargeResult r3 = service.chargeCumulative(
                PARTNER, "T3", usd("450"), usd("1000"), null, null);
        assertEquals(0, r3.dailyUsage().compareTo(usd("1000")));
    }

    @Test
    @Transactional
    @DisplayName("idempotent by txnRef: a repeat charge for the same txn does not double-count")
    void idempotentByTxnRef() {
        service.chargeCumulative(PARTNER, "T1", usd("300"), usd("1000"), null, null);
        PrefundingService.CumulativeChargeResult again = service.chargeCumulative(
                PARTNER, "T1", usd("300"), usd("1000"), null, null);
        assertEquals(0, again.dailyUsage().compareTo(usd("300")), "repeat of same txnRef is a no-op");
    }

    @Test
    @Transactional
    @DisplayName("cumulative breach of the daily cap is rejected (the second txn pushes over)")
    void breachesDailyCap() {
        service.chargeCumulative(PARTNER, "T1", usd("800"), usd("1000"), null, null);   // 800 ≤ 1000 ok
        // T2 would push to 1100 > 1000 → rejected BEFORE any charge row is written (breach precedes the save).
        ApiException ex = assertThrows(ApiException.class, () ->
                service.chargeCumulative(PARTNER, "T2", usd("300"), usd("1000"), null, null));
        assertEquals(ErrorCode.CUMULATIVE_LIMIT_EXCEEDED, ex.errorCode());
    }

    @Test
    @Transactional
    @DisplayName("null caps are unconstrained; monthly/annual caps enforced independently")
    void nullCapsUnconstrained_andPeriodIsolation() {
        // All caps null → any amount passes.
        service.chargeCumulative(PARTNER, "T1", usd("999999"), null, null, null);
        // A monthly-cap breach (daily null) is still caught: 999999 + 2 = 1000001 > 1000000.
        ApiException ex = assertThrows(ApiException.class, () ->
                service.chargeCumulative(PARTNER, "T2", usd("2"), null, usd("1000000"), null));
        assertEquals(ErrorCode.CUMULATIVE_LIMIT_EXCEEDED, ex.errorCode());
    }

    @Test
    @Transactional
    @DisplayName("reverse nets the charge back to zero and is idempotent")
    void reverseNetsBackAndIdempotent() {
        service.chargeCumulative(PARTNER, "T1", usd("500"), usd("1000"), null, null);

        PrefundingService.CumulativeReverseResult rev = service.reverseCumulative(PARTNER, "T1");
        assertEquals(0, rev.reversedAmount().compareTo(usd("500")));

        // After reverse, the same partner can charge another 1000 (period usage is back to 0).
        PrefundingService.CumulativeChargeResult after = service.chargeCumulative(
                PARTNER, "T2", usd("1000"), usd("1000"), null, null);
        assertEquals(0, after.dailyUsage().compareTo(usd("1000")), "reversed charge freed the cap");

        // Reversing again is a no-op.
        PrefundingService.CumulativeReverseResult again = service.reverseCumulative(PARTNER, "T1");
        assertEquals(0, again.reversedAmount().signum(), "second reverse is idempotent no-op");
    }

    @Test
    @Transactional
    @DisplayName("reverse of a never-charged txn is a no-op")
    void reverseUnknownTxnNoOp() {
        PrefundingService.CumulativeReverseResult rev = service.reverseCumulative(PARTNER, "NEVER");
        assertEquals(0, rev.reversedAmount().signum());
    }

    @Test
    @Transactional
    @DisplayName("velocity: the daily transaction-COUNT cap rejects the (limit+1)-th txn (amount caps null)")
    void dailyTxnCountCap() {
        // limit = 2 txns/day, no amount caps. T1 + T2 ok; T3 trips the velocity cap.
        service.chargeCumulative(PARTNER, "T1", usd("10"), null, null, null, 2);
        service.chargeCumulative(PARTNER, "T2", usd("10"), null, null, null, 2);
        ApiException ex = assertThrows(ApiException.class, () ->
                service.chargeCumulative(PARTNER, "T3", usd("10"), null, null, null, 2));
        assertEquals(ErrorCode.CUMULATIVE_LIMIT_EXCEEDED, ex.errorCode());
    }

    @Test
    @Transactional
    @DisplayName("velocity: reversing a charge frees a count slot (net count drops)")
    void reverseFreesCountSlot() {
        service.chargeCumulative(PARTNER, "T1", usd("10"), null, null, null, 1);   // 1/1
        service.reverseCumulative(PARTNER, "T1");                                  // net count → 0
        service.chargeCumulative(PARTNER, "T2", usd("10"), null, null, null, 1);   // slot freed → 1/1 ok
        // The slot is genuinely consumed again: a third active charge now trips the cap (breach is last).
        assertThrows(ApiException.class, () ->
                service.chargeCumulative(PARTNER, "T3", usd("10"), null, null, null, 1));
    }
}
