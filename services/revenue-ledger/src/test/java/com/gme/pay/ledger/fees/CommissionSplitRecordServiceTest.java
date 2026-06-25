package com.gme.pay.ledger.fees;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gme.pay.ledger.persistence.CommissionSplitRecordEntity;
import com.gme.pay.ledger.persistence.CommissionSplitRecordRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots revenue-ledger against H2 (Flyway V005) to verify the live commission-split sink (Step 7):
 * {@link CommissionSplitRecordService} runs the {@link CommissionSplitCalculator} and persists the
 * two-sided split to {@code commission_splits}, idempotent by {@code txnRef}.
 *
 * <p>Outbox poll pushed out so the scheduled tick is inert during the test.
 */
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
class CommissionSplitRecordServiceTest {

    @Autowired
    private CommissionSplitRecordService service;

    @Autowired
    private CommissionSplitRecordRepository repository;

    @Test
    void recordsTheTwoSidedSplit_workedExample() {
        // payout=100,000 KRW, merchant=2.00%, van=0.20%, gmeShare=70%, partnerShare=30%
        // → gross=2000, van=200, net=1800, gmeGross=1260, scheme=540, partner=378, gmeNet=882
        CommissionSplitRecordService.Result r = service.recordIfAbsent(
                "CS-TXN-1", 7L, 1L, LocalDate.of(2026, 6, 15),
                100_000L, new BigDecimal("0.0200"), new BigDecimal("0.0020"),
                new BigDecimal("0.70"), new BigDecimal("0.30"));

        assertTrue(r.created(), "first capture inserts");
        CommissionSplitRecordEntity e = r.record();
        assertEquals(1800L, e.getNetMerchantFeeKrw());
        assertEquals(540L, e.getSchemeShareKrw());
        assertEquals(1260L, e.getGmeGrossShareKrw());
        assertEquals(378L, e.getPartnerShareKrw());
        assertEquals(882L, e.getGmeNetShareKrw());
        // Invariants: scheme split + partner split both conserve KRW exactly.
        assertEquals(e.getNetMerchantFeeKrw(), e.getGmeGrossShareKrw() + e.getSchemeShareKrw());
        assertEquals(e.getGmeGrossShareKrw(), e.getPartnerShareKrw() + e.getGmeNetShareKrw());
    }

    @Test
    void isIdempotentByTxnRef() {
        service.recordIfAbsent("CS-DUP", 8L, 1L, LocalDate.of(2026, 6, 16),
                100_000L, new BigDecimal("0.0200"), new BigDecimal("0.0020"),
                new BigDecimal("0.70"), new BigDecimal("0.30"));
        // A replay with DIFFERENT inputs must NOT overwrite or insert a second row.
        CommissionSplitRecordService.Result replay = service.recordIfAbsent(
                "CS-DUP", 8L, 1L, LocalDate.of(2026, 6, 16),
                999_999L, new BigDecimal("0.0500"), new BigDecimal("0.0010"),
                new BigDecimal("0.50"), new BigDecimal("0.50"));

        assertFalse(replay.created(), "replay returns the existing row, does not insert");
        assertEquals(378L, replay.record().getPartnerShareKrw(), "first write wins");
        assertEquals(1, repository.findAll().stream()
                .filter(x -> "CS-DUP".equals(x.getTxnRef())).count(), "exactly one row");
    }
}
