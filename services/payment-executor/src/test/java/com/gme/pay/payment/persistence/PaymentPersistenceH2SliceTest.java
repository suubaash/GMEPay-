package com.gme.pay.payment.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.payment.domain.PaymentMode;
import com.gme.pay.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * No-Docker unit slice over the payment-executor persistence layer. Uses the Flyway-managed
 * H2 (PostgreSQL mode) datasource from {@code application.properties}, proving the V001/V002
 * migrations stay H2-compatible and the repositories round-trip locally.
 *
 * <p>The authoritative real-engine checks live in {@link ExecutionAttemptPostgresIT} and
 * {@link IdempotencyPostgresIT} (docker-tagged, CI-only per 17.2-G08).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PaymentPersistenceH2SliceTest {

    @Autowired
    private ExecutionAttemptRepository attempts;

    @Autowired
    private IdempotencyRecordRepository records;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void executionAttemptRoundTripsWithSettlementSnapshot() {
        Instant created = Instant.parse("2026-06-09T10:15:30Z");
        ExecutionAttemptEntity attempt = new ExecutionAttemptEntity(
                "TXN-H2-001", 42L, "PTN-REF-001", "zeropay",
                PaymentMode.MPM, PaymentStatus.APPROVED, created);
        attempt.setSettlementSnapshot(
                new BigDecimal("10500.56"), RoundingMode.DOWN, new BigDecimal("0.007"), "KRW");
        attempt.setCompletedAt(created.plusSeconds(2));

        Long id = attempts.saveAndFlush(attempt).getId();
        entityManager.clear();

        ExecutionAttemptEntity reloaded = attempts.findById(id).orElseThrow();
        assertThat(reloaded.getOutcome()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(reloaded.getBookedSettlementAmount()).isEqualByComparingTo("10500.56");
        assertThat(reloaded.getSettlementRoundingMode()).isEqualTo(RoundingMode.DOWN);
        assertThat(reloaded.getRoundingResidual()).isEqualByComparingTo("0.007");
        assertThat(reloaded.getSettlementCurrency()).isEqualTo("KRW");
        assertThat(attempts.findByTxnRefOrderByCreatedAtAscIdAsc("TXN-H2-001")).hasSize(1);
    }

    @Test
    void idempotencyRecordRoundTripsAndDuplicateIsRejected() {
        Instant now = Instant.parse("2026-06-09T09:00:00Z");
        IdempotencyRecordEntity record = new IdempotencyRecordEntity(
                7L, "h2-key", "hash-1", now);
        record.recordOutcome(PaymentStatus.APPROVED, "{\"targetPayout\":\"10.20\"}");
        records.saveAndFlush(record);
        entityManager.clear();

        assertThat(records.findByPartnerIdAndIdempotencyKey(7L, "h2-key"))
                .isPresent()
                .hasValueSatisfying(r -> {
                    assertThat(r.getResponseStatus()).isEqualTo(PaymentStatus.APPROVED);
                    assertThat(r.getResponseBody()).contains("\"targetPayout\":\"10.20\"");
                });

        assertThatThrownBy(() -> records.saveAndFlush(
                new IdempotencyRecordEntity(7L, "h2-key", "hash-2", now.plusSeconds(1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
