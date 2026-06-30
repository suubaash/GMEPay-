package com.gme.pay.scheme.zeropay.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Persistence contract for {@code zp_committed_txns} (V003): JPA mapping + repository finders
 * + the unique {@code (txn_kind, zeropay_txn_ref)} idempotency guard. H2 in PostgreSQL mode,
 * no Docker.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ZpCommittedTxnPersistenceH2SliceTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 6, 9);

    @Autowired
    private ZpCommittedTxnRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    @DisplayName("committed payment round-trips amounts, fees, partner type and approval code")
    void paymentRoundTrip() {
        ZpCommittedTxnEntity payment = ZpCommittedTxnEntity.payment(
                "GME0000000000000001", "ZP20260609000001", "M0000001", "QR0000000000000001",
                BUSINESS_DATE, LocalTime.of(10, 30, 0),
                new BigDecimal("50000"), new BigDecimal("250"), new BigDecimal("100"),
                "D", "AP0000000001", null);

        Long id = repository.saveAndFlush(payment).getId();
        entityManager.clear();

        ZpCommittedTxnEntity loaded = repository.findById(id).orElseThrow();
        assertEquals(ZpCommittedTxnEntity.KIND_PAYMENT, loaded.getTxnKind());
        assertEquals("ZP20260609000001", loaded.getZeropayTxnRef());
        assertEquals("M0000001", loaded.getMerchantId());
        assertEquals(0, loaded.getAmountKrw().compareTo(new BigDecimal("50000")));
        assertEquals(0, loaded.getMerchantFeeKrw().compareTo(new BigDecimal("250")));
        assertEquals(0, loaded.getVanFeeKrw().compareTo(new BigDecimal("100")));
        assertEquals("D", loaded.getPartnerType());
        assertEquals("AP0000000001", loaded.getApprovalCode());
        assertEquals(ZpCommittedTxnEntity.STATUS_APPROVED, loaded.getStatusCode());
        assertNull(loaded.getOriginalApprovalCode());
        assertNotNull(loaded.getCreatedAt());
    }

    @Test
    @DisplayName("refund carries the original approval code and refunded status")
    void refundRoundTrip() {
        ZpCommittedTxnEntity refund = ZpCommittedTxnEntity.refund(
                "GME0000000000000002", "ZP20260609000002", "M0000001", "QR0000000000000001",
                BUSINESS_DATE, LocalTime.of(15, 0, 0),
                new BigDecimal("50000"), new BigDecimal("250"), new BigDecimal("100"),
                "D", "RF0000000001", "AP0000000001", null);

        Long id = repository.saveAndFlush(refund).getId();
        entityManager.clear();

        ZpCommittedTxnEntity loaded = repository.findById(id).orElseThrow();
        assertEquals(ZpCommittedTxnEntity.KIND_REFUND, loaded.getTxnKind());
        assertEquals(ZpCommittedTxnEntity.STATUS_REFUNDED, loaded.getStatusCode());
        assertEquals("RF0000000001", loaded.getApprovalCode());
        assertEquals("AP0000000001", loaded.getOriginalApprovalCode());
    }

    @Test
    @DisplayName("finders return business-date scoped rows ordered by time then id")
    void finderOrdering() {
        repository.save(ZpCommittedTxnEntity.payment(
                "GME2", "ZP2", "M2", "QR2", BUSINESS_DATE, LocalTime.of(11, 0, 0),
                new BigDecimal("20000"), BigDecimal.ZERO, BigDecimal.ZERO, "D", "AP2", null));
        repository.save(ZpCommittedTxnEntity.payment(
                "GME1", "ZP1", "M1", "QR1", BUSINESS_DATE, LocalTime.of(9, 0, 0),
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO, "D", "AP1", null));
        repository.save(ZpCommittedTxnEntity.refund(
                "GME3", "ZP3", "M1", "QR1", BUSINESS_DATE, LocalTime.of(12, 0, 0),
                new BigDecimal("5000"), BigDecimal.ZERO, BigDecimal.ZERO, "D", "RF3", "AP1", null));
        repository.flush();
        entityManager.clear();

        List<ZpCommittedTxnEntity> payments = repository
                .findByBusinessDateAndTxnKindOrderByTxnTimeAscIdAsc(
                        BUSINESS_DATE, ZpCommittedTxnEntity.KIND_PAYMENT);
        assertEquals(2, payments.size());
        assertEquals("ZP1", payments.get(0).getZeropayTxnRef());  // 09:00 first
        assertEquals("ZP2", payments.get(1).getZeropayTxnRef());

        List<ZpCommittedTxnEntity> refunds = repository
                .findByBusinessDateAndTxnKindOrderByTxnTimeAscIdAsc(
                        BUSINESS_DATE, ZpCommittedTxnEntity.KIND_REFUND);
        assertEquals(1, refunds.size());

        assertEquals(3, repository.findByBusinessDateOrderByMerchantIdAscTxnTimeAsc(BUSINESS_DATE).size());
        assertEquals(0, repository.findByBusinessDateOrderByMerchantIdAscTxnTimeAsc(
                BUSINESS_DATE.minusDays(1)).size());
    }

    @Test
    @DisplayName("duplicate (txn_kind, zeropay_txn_ref) is rejected — idempotent capture guard")
    void duplicateRefRejected() {
        repository.saveAndFlush(ZpCommittedTxnEntity.payment(
                "GME1", "ZPDUP", "M1", "QR1", BUSINESS_DATE, LocalTime.of(9, 0, 0),
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO, "D", "AP1", null));

        ZpCommittedTxnEntity dup = ZpCommittedTxnEntity.payment(
                "GME1b", "ZPDUP", "M1", "QR1", BUSINESS_DATE, LocalTime.of(9, 5, 0),
                new BigDecimal("10000"), BigDecimal.ZERO, BigDecimal.ZERO, "D", "AP1b", null);

        assertThrows(DataIntegrityViolationException.class, () -> repository.saveAndFlush(dup));
    }
}
