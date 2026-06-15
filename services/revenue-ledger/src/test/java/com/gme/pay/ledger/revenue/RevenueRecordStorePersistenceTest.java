package com.gme.pay.ledger.revenue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Boots the revenue-ledger context against H2 (no Docker) to verify the JPA-backed
 * {@code RevenueRecordStore} (Flyway V004 + entity + repo) persists records and that
 * {@link RevenueRecordService#getRevenueByPartner} now returns REAL aggregates — fx margin,
 * service charge, txn count and a representative currency — instead of the prior all-zeros.
 *
 * <p>Outbox poll pushed out so the scheduled tick is inert during the test.
 */
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
class RevenueRecordStorePersistenceTest {

    @Autowired
    private RevenueRecordStore store; // @Primary JpaRevenueRecordStore

    @Autowired
    private RevenueRecordService service;

    @Test
    void jpaStore_persistsAndAggregatesRealRevenue() {
        LocalDate d = LocalDate.of(2026, 6, 15);
        // partner 7: one cross-border (fx margin 1.50) + one same-currency (fx margin 0); both KRW 500 fee.
        store.save(RevenueRecord.of("TXN-A", 7L, 1L, d,
                new BigDecimal("1.00"), new BigDecimal("0.50"),
                new BigDecimal("500"), "KRW", new BigDecimal("0.70")));
        store.save(RevenueRecord.sameCurrency("TXN-B", 7L, 1L, d,
                new BigDecimal("500"), "KRW", new BigDecimal("0.70")));
        // a different partner — must not leak into partner 7's aggregate.
        store.save(RevenueRecord.of("TXN-C", 99L, 1L, d,
                new BigDecimal("2.00"), BigDecimal.ZERO,
                new BigDecimal("300"), "KRW", new BigDecimal("0.70")));

        RevenueAggregate agg = service.getRevenueByPartner(7L, d.minusDays(1), d.plusDays(1));

        assertEquals(7L, agg.partnerId());
        assertEquals(2L, agg.txnCount(), "two revenue rows for partner 7");
        assertEquals(0, agg.totalFxMarginUsd().compareTo(new BigDecimal("1.50")),
                "fx margin = (1.00+0.50) + 0 (same-ccy)");
        assertEquals(0, agg.totalServiceChargeAmount().compareTo(new BigDecimal("1000")),
                "service charge = 500 + 500");
        assertEquals("KRW", agg.serviceChargeCcy());
    }

    @Test
    void save_isIdempotentByTxnRef() {
        LocalDate d = LocalDate.of(2026, 6, 16);
        store.save(RevenueRecord.of("TXN-DUP", 8L, 1L, d,
                new BigDecimal("5.00"), BigDecimal.ZERO,
                new BigDecimal("100"), "KRW", new BigDecimal("0.70")));
        // second save of the SAME txnRef must be a no-op (idempotent), not a second row.
        store.save(RevenueRecord.of("TXN-DUP", 8L, 1L, d,
                new BigDecimal("999.00"), BigDecimal.ZERO,
                new BigDecimal("999"), "KRW", new BigDecimal("0.70")));

        RevenueAggregate agg = service.getRevenueByPartner(8L, d, d);
        assertEquals(1L, agg.txnCount(), "duplicate txnRef must not create a second row");
        assertEquals(0, agg.totalFxMarginUsd().compareTo(new BigDecimal("5.00")), "first write wins");
        assertEquals(0, agg.totalServiceChargeAmount().compareTo(new BigDecimal("100")));
    }
}
