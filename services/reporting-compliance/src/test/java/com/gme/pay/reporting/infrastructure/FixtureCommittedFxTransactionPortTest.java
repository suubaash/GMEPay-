package com.gme.pay.reporting.infrastructure;

import com.gme.pay.reporting.domain.CommittedTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FixtureCommittedFxTransactionPort} — the in-process
 * rate-locked committed-FX source that supplies {@code offerRateColl} (FX1015 #14)
 * until transaction-mgmt exposes a real committed-FX endpoint (INTEGRATION REQUEST #1).
 */
class FixtureCommittedFxTransactionPortTest {

    private static final LocalDate DATE = LocalDate.of(2026, 5, 20);

    private CommittedTransaction seedTxn(long txnId, long partnerId, Instant committedAt) {
        return FixtureCommittedFxTransactionPort.inbound(
                txnId, "REF-" + txnId, partnerId,
                new BigDecimal("38.99"), "USD",
                new BigDecimal("50000"), "KRW",
                new BigDecimal("1.01010103"),   // offerRateColl — FX1015 #14
                new BigDecimal("1316.25000000"),
                new BigDecimal("37.04"), committedAt);
    }

    @Test
    @DisplayName("empty by default (no synthetic data on a clean boot)")
    void emptyByDefault() {
        FixtureCommittedFxTransactionPort port = new FixtureCommittedFxTransactionPort();
        assertTrue(port.fetchCommittedFx(DATE, DATE, null).isEmpty());
    }

    @Test
    @DisplayName("seeded transaction carries a populated offerRateColl (FX1015 #14)")
    void seeded_carriesOfferRateColl() {
        FixtureCommittedFxTransactionPort port = new FixtureCommittedFxTransactionPort();
        port.seed(seedTxn(3001L, 42L, Instant.parse("2026-05-20T03:00:00Z")));

        List<CommittedTransaction> result = port.fetchCommittedFx(DATE, DATE, null);
        assertEquals(1, result.size());
        assertNotNull(result.get(0).getOfferRateColl());
        assertEquals(0, new BigDecimal("1.01010103").compareTo(result.get(0).getOfferRateColl()));
    }

    @Test
    @DisplayName("filters by KST report-date window")
    void filtersByDate() {
        FixtureCommittedFxTransactionPort port = new FixtureCommittedFxTransactionPort();
        // 2026-05-20T16:00:00Z is 2026-05-21 in KST (UTC+9) -> outside a 05-20 window.
        port.seed(seedTxn(1L, 42L, Instant.parse("2026-05-20T16:00:00Z")));
        port.seed(seedTxn(2L, 42L, Instant.parse("2026-05-20T03:00:00Z")));

        List<CommittedTransaction> result = port.fetchCommittedFx(DATE, DATE, null);
        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).getTxnId());
    }

    @Test
    @DisplayName("filters by partnerId when supplied")
    void filtersByPartner() {
        FixtureCommittedFxTransactionPort port = new FixtureCommittedFxTransactionPort();
        port.seed(seedTxn(1L, 42L, Instant.parse("2026-05-20T03:00:00Z")));
        port.seed(seedTxn(2L, 99L, Instant.parse("2026-05-20T03:00:00Z")));

        assertEquals(1, port.fetchCommittedFx(DATE, DATE, 42L).size());
        assertEquals(2, port.fetchCommittedFx(DATE, DATE, null).size());
    }
}
