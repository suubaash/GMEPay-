package com.gme.pay.txn.persistence;

import com.gme.pay.txn.api.dto.TransactionResponse;
import com.gme.pay.txn.domain.model.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * T4-4: {@code transactions.merchant_name} (V012) round-trips, and — the part that matters — a row
 * that has no name keeps NOT having one.
 *
 * <p>The gap this closes was not "the column was wrong", it was "there was no column", so these tests
 * pin both halves of the contract: a captured name survives save → rehydrate → API response, and an
 * absent name stays null the whole way instead of being back-filled or substituted anywhere.
 *
 * <p>Runs Flyway against the in-memory H2 (PostgreSQL-mode) datasource — no Docker.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.flyway.enabled=true"
})
class MerchantNamePersistenceIT {

    @Autowired
    private TransactionRepository jpaRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private static Transaction walletTxn() {
        return new Transaction(
                7L, "SENDMN-" + java.util.UUID.randomUUID(), "sendmn", "OVERSEAS", "MPM",
                new BigDecimal("350000.00000000"), "MNT",
                new BigDecimal("100000.00000000"), "KRW",
                "M-778", null);
    }

    @Test
    @DisplayName("V012: a captured merchant name round-trips save → rehydrate → TransactionResponse")
    void merchantNameRoundTrips() {
        Transaction txn = walletTxn();
        txn.applyMerchantName("Ulaanbaatar Central Store");

        jpaRepository.save(TransactionEntityMapper.toEntity(txn));
        jpaRepository.flush();

        Optional<TransactionEntity> loaded = jpaRepository.findById(txn.txnRef());
        assertTrue(loaded.isPresent(), "transaction should be persisted");
        assertEquals("Ulaanbaatar Central Store", loaded.get().getMerchantName(),
                "merchant_name must be written to the column, not held in memory only");

        Transaction rehydrated = TransactionEntityMapper.toDomain(loaded.get());
        assertEquals("Ulaanbaatar Central Store", rehydrated.merchantName(),
                "rehydration must replay the name (a later read is the only reader that matters)");

        // The read path the portal/BFF actually consumes.
        TransactionResponse response = TransactionResponse.from(rehydrated);
        assertEquals("Ulaanbaatar Central Store", response.merchantName(),
                "TransactionResponse.merchantName must expose the stored name, not a hardcoded null");
        assertEquals("M-778", response.merchantId(),
                "merchantId must keep its own value — the two fields are not interchangeable");
    }

    @Test
    @DisplayName("null merchant name is PRESERVED end-to-end — never defaulted to merchantId or a label")
    void absentMerchantNameStaysNull() {
        Transaction txn = walletTxn();       // no applyMerchantName call at all

        jpaRepository.save(TransactionEntityMapper.toEntity(txn));
        jpaRepository.flush();

        TransactionEntity loaded = jpaRepository.findById(txn.txnRef()).orElseThrow();
        assertNull(loaded.getMerchantName(), "an unresolved name must persist as NULL");

        Transaction rehydrated = TransactionEntityMapper.toDomain(loaded);
        assertNull(rehydrated.merchantName());

        TransactionResponse response = TransactionResponse.from(rehydrated);
        assertNull(response.merchantName(),
                "null must stay null so the UI shows an em dash; substituting merchantId would forge it");
        assertNotNull(response.merchantId(), "…while merchantId is still returned as itself");
    }

    @Test
    @DisplayName("migration leaves HISTORICAL rows null — V012 back-fills nothing")
    void historicalRowsAreNotBackFilled() {
        // A row inserted WITHOUT merchant_name — i.e. exactly the shape every row written before V012
        // has. The column must exist (so the read compiles/queries) and be null (so no name is claimed).
        String txnRef = "legacy-" + java.util.UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transactions
                    (txn_ref, partner_ref, send_amount, send_ccy, target_payout, target_ccy,
                     status, created_at, updated_at, merchant_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                txnRef, "legacy-partner",
                new BigDecimal("50000.00000000"), "KRW",
                new BigDecimal("50000.00000000"), "KRW",
                "APPROVED", java.sql.Timestamp.from(java.time.Instant.now()),
                java.sql.Timestamp.from(java.time.Instant.now()), "M-LEGACY");

        String stored = jdbc.queryForObject(
                "SELECT merchant_name FROM transactions WHERE txn_ref = ?", String.class, txnRef);
        assertNull(stored,
                "V012 must not guess a name for rows that never captured one (no DEFAULT, no UPDATE)");

        TransactionEntity loaded = jpaRepository.findById(txnRef).orElseThrow();
        assertNull(TransactionEntityMapper.toDomain(loaded).merchantName(),
                "a legacy row reads back as 'name unknown', which is the honest answer");
    }
}
