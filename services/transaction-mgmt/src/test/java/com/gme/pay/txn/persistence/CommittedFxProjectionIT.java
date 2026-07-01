package com.gme.pay.txn.persistence;

import com.gme.pay.txn.domain.model.Transaction;
import com.gme.pay.txn.domain.model.TransactionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Verifies the V007 migration applies (committed-FX + refund columns) and the
 * findCommittedFx / findRefundedOn queries work against H2 (PostgreSQL mode). No Docker.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class CommittedFxProjectionIT {

    @Autowired
    private TransactionRepository jpaRepository;

    private static Transaction committedTxn(Instant committedAt) {
        Transaction txn = new Transaction(
                700L, "PTX-1", "zeropay_kr", "OUTBOUND", "CPM",
                new BigDecimal("130000.00000000"), "KRW",
                new BigDecimal("11000000.00000000"), "IDR",
                "M-1", "Q-1");
        txn.applyStatus(TransactionStatus.APPROVED);
        txn.applyStatusPatch("SCH-1", "AP-1", new BigDecimal("673.07690000"),
                committedAt, null, null, null);
        txn.captureCommittedFxAtCommit(new BigDecimal("6.73080000"), new BigDecimal("6.73080000"),
                committedAt);
        return txn;
    }

    @Test
    @DisplayName("V007 columns round-trip through the mapper")
    void committedFxRoundTrips() {
        Instant committedAt = Instant.parse("2026-06-20T03:00:00Z");
        Transaction txn = committedTxn(committedAt);
        jpaRepository.save(TransactionEntityMapper.toEntity(txn));
        jpaRepository.flush();

        Transaction loaded = TransactionEntityMapper.toDomain(
                jpaRepository.findById(txn.txnRef()).orElseThrow());
        assertNotNull(loaded.offerRateColl());
        assertNotNull(loaded.crossRate());
        assertEquals(0, loaded.usdAmount().compareTo(new BigDecimal("673.07690000")));
        assertEquals(0, loaded.collectionMarginUsd().compareTo(new BigDecimal("6.73080000")));
        assertFalse(loaded.sameCcyShortcircuit());
        assertEquals(committedAt, loaded.committedAt());
    }

    @Test
    @DisplayName("findCommittedFx returns committed rows in window, filters by partner")
    void findCommittedFxWindow() {
        Instant inWindow = Instant.parse("2026-06-20T03:00:00Z");
        Transaction txn = committedTxn(inWindow);
        jpaRepository.save(TransactionEntityMapper.toEntity(txn));
        jpaRepository.flush();

        Instant from = LocalDate.of(2026, 6, 20).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = LocalDate.of(2026, 6, 21).atStartOfDay().toInstant(ZoneOffset.UTC);

        assertEquals(1, jpaRepository.findCommittedFx(from, to, 700L).size());
        assertEquals(1, jpaRepository.findCommittedFx(from, to, null).size());
        // wrong partner / out of window → empty
        assertTrue(jpaRepository.findCommittedFx(from, to, 999L).isEmpty());
        Instant priorFrom = LocalDate.of(2026, 6, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant priorTo = LocalDate.of(2026, 6, 2).atStartOfDay().toInstant(ZoneOffset.UTC);
        assertTrue(jpaRepository.findCommittedFx(priorFrom, priorTo, null).isEmpty());
    }

    @Test
    @DisplayName("findRefundedOn returns rows whose refundedAt is on the requested day")
    void findRefundedOnDay() {
        Instant refundedAt = Instant.parse("2026-06-25T08:00:00Z");
        Transaction txn = committedTxn(Instant.parse("2026-06-20T03:00:00Z"));
        txn.applyStatus(TransactionStatus.REFUNDED);
        txn.applyRefundEnrichment(new BigDecimal("130000.00000000"), "QR-1",
                refundedAt, "ORIG-SCH-1");
        jpaRepository.save(TransactionEntityMapper.toEntity(txn));
        jpaRepository.flush();

        Instant from = LocalDate.of(2026, 6, 25).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = LocalDate.of(2026, 6, 26).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<TransactionEntity> hits = jpaRepository.findRefundedOn(from, to);
        assertEquals(1, hits.size());
        assertEquals("ORIG-SCH-1", hits.get(0).getOriginalPaymentTxnRef());
        assertEquals(0, hits.get(0).getRefundAmountKrw().compareTo(new BigDecimal("130000")));

        // a different day → empty
        Instant otherFrom = LocalDate.of(2026, 6, 24).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant otherTo = LocalDate.of(2026, 6, 25).atStartOfDay().toInstant(ZoneOffset.UTC);
        assertTrue(jpaRepository.findRefundedOn(otherFrom, otherTo).isEmpty());
    }
}
