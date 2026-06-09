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
import java.math.RoundingMode;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Integration test exercising Flyway migration + JPA mapping + the rounding-lock
 * fields end-to-end against the in-memory H2 (PostgreSQL-mode) datasource
 * declared in {@code application.properties}.
 *
 * <p>No Docker, no Testcontainers; plain JUnit 5.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class TransactionPersistenceIT {

    @Autowired
    private TransactionRepository jpaRepository;

    private static Transaction newDomainTxn() {
        return new Transaction(
                "partner-acme",
                new BigDecimal("100.00000000"), "USD",
                new BigDecimal("130000.00000000"), "KRW");
    }

    @Test
    @DisplayName("save + retrieve round-trips all columns including the three rounding-lock fields")
    void saveAndRetrieveWithRoundingLock() {
        Transaction txn = newDomainTxn();
        txn.applyRoundingLock(
                new BigDecimal("10500.56000000"),
                RoundingMode.DOWN,
                new BigDecimal("0.00700000"));

        TransactionEntity entity = TransactionEntityMapper.toEntity(txn);
        jpaRepository.save(entity);
        jpaRepository.flush();

        Optional<TransactionEntity> loadedOpt = jpaRepository.findById(txn.txnRef());
        assertTrue(loadedOpt.isPresent(), "transaction should be persisted");
        TransactionEntity loaded = loadedOpt.get();

        // Basic columns
        assertEquals("partner-acme", loaded.getPartnerRef());
        assertEquals("USD", loaded.getSendCcy());
        assertEquals("KRW", loaded.getTargetCcy());
        assertEquals(0, loaded.getSendAmount().compareTo(new BigDecimal("100.00000000")));
        assertEquals(0, loaded.getTargetPayout().compareTo(new BigDecimal("130000.00000000")));
        assertEquals("CREATED", loaded.getStatus());

        // Rate-lock fields
        assertNotNull(loaded.getBookedSettlementAmount(), "bookedSettlementAmount must persist");
        assertEquals(0, loaded.getBookedSettlementAmount().compareTo(new BigDecimal("10500.56000000")));
        assertEquals("DOWN", loaded.getSettlementRoundingMode());
        assertEquals(0, loaded.getRoundingResidual().compareTo(new BigDecimal("0.00700000")));

        // Round-trip back through the mapper preserves the typed RoundingMode enum.
        Transaction rehydrated = TransactionEntityMapper.toDomain(loaded);
        assertEquals(RoundingMode.DOWN, rehydrated.settlementRoundingMode());
        assertEquals(0, rehydrated.bookedSettlementAmount().compareTo(new BigDecimal("10500.56000000")));
        assertEquals(0, rehydrated.roundingResidual().compareTo(new BigDecimal("0.00700000")));
    }

    @Test
    @DisplayName("state transitions persist correctly across save/reload cycles")
    void stateTransitionsPersist() {
        Transaction txn = newDomainTxn();
        assertEquals(TransactionStatus.CREATED, txn.status());

        // CREATED row
        jpaRepository.save(TransactionEntityMapper.toEntity(txn));
        jpaRepository.flush();

        // CREATED → PENDING_DEBIT, persisted
        txn.applyStatus(TransactionStatus.PENDING_DEBIT);
        jpaRepository.save(TransactionEntityMapper.toEntity(txn));
        jpaRepository.flush();

        Transaction afterPending =
                TransactionEntityMapper.toDomain(jpaRepository.findById(txn.txnRef()).orElseThrow());
        assertEquals(TransactionStatus.PENDING_DEBIT, afterPending.status());
        // Rounding-lock not yet populated.
        assertNull(afterPending.bookedSettlementAmount());
        assertNull(afterPending.settlementRoundingMode());
        assertNull(afterPending.roundingResidual());

        // PENDING_DEBIT → APPROVED with rate-lock applied at commit
        txn.applyStatus(TransactionStatus.APPROVED);
        txn.applyRoundingLock(
                new BigDecimal("50000.00000000"),
                RoundingMode.HALF_UP,
                BigDecimal.ZERO.setScale(8));
        jpaRepository.save(TransactionEntityMapper.toEntity(txn));
        jpaRepository.flush();

        Transaction afterApproved =
                TransactionEntityMapper.toDomain(jpaRepository.findById(txn.txnRef()).orElseThrow());
        assertEquals(TransactionStatus.APPROVED, afterApproved.status());
        assertEquals(RoundingMode.HALF_UP, afterApproved.settlementRoundingMode());
        assertEquals(0, afterApproved.bookedSettlementAmount().compareTo(new BigDecimal("50000.00000000")));
        assertEquals(0, afterApproved.roundingResidual().compareTo(BigDecimal.ZERO));
    }
}
