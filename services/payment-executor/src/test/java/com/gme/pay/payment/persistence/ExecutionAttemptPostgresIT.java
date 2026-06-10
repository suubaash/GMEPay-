package com.gme.pay.payment.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.payment.domain.Direction;
import com.gme.pay.payment.domain.PaymentMode;
import com.gme.pay.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-PostgreSQL integration test for {@code execution_attempts} (ticket 17.2-G08).
 *
 * <p>Runs Flyway V001/V002 against postgres:16 via Testcontainers and verifies the
 * repository round-trip including the rate-locked settlement snapshot fields (booked
 * amount / rounding mode / residual) and the PG CHECK constraints — the parts where
 * H2-in-PG-mode could silently diverge from the real engine.
 *
 * <p>Docker-only: excluded from the normal {@code test} task by the {@code docker} tag and
 * executed by {@code integrationTest} on CI ubuntu runners. Never run locally (no Docker).
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ExecutionAttemptPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private ExecutionAttemptRepository attempts;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayMigrationsAppliedSuccessfullyOnPostgres16() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(2);

        // Both tables exist and are queryable on the real engine.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM execution_attempts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys", Integer.class)).isZero();
    }

    @Test
    void approvedAttemptRoundTripsWithSettlementSnapshot() {
        Instant created = Instant.parse("2026-06-09T10:15:30Z");
        ExecutionAttemptEntity attempt = new ExecutionAttemptEntity(
                "TXN-PG-001", 42L, "PTN-REF-001", "zeropay",
                PaymentMode.MPM, PaymentStatus.APPROVED, created);
        attempt.setPaymentId("PAY-PG-001");
        attempt.setDirection(Direction.INBOUND);
        attempt.setSchemeTxnRef("SCH-PG-001");
        attempt.setPrefundDeductedUsd(new BigDecimal("36.97140000"));
        // MONEY_CONVENTION.md worked example: precise 10500.567, partner mode DOWN, scale 2
        attempt.setSettlementSnapshot(
                new BigDecimal("10500.56"), RoundingMode.DOWN, new BigDecimal("0.007"), "KRW");
        attempt.setCompletedAt(created.plusSeconds(2));

        Long id = attempts.saveAndFlush(attempt).getId();
        entityManager.clear(); // force a real DB read, not a first-level-cache hit

        ExecutionAttemptEntity reloaded = attempts.findById(id).orElseThrow();
        assertThat(reloaded.getTxnRef()).isEqualTo("TXN-PG-001");
        assertThat(reloaded.getPaymentId()).isEqualTo("PAY-PG-001");
        assertThat(reloaded.getPartnerId()).isEqualTo(42L);
        assertThat(reloaded.getPartnerTxnRef()).isEqualTo("PTN-REF-001");
        assertThat(reloaded.getSchemeId()).isEqualTo("zeropay");
        assertThat(reloaded.getPaymentMode()).isEqualTo(PaymentMode.MPM);
        assertThat(reloaded.getDirection()).isEqualTo(Direction.INBOUND);
        assertThat(reloaded.getOutcome()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(reloaded.getSchemeTxnRef()).isEqualTo("SCH-PG-001");
        assertThat(reloaded.getPrefundDeductedUsd()).isEqualByComparingTo("36.97140000");
        assertThat(reloaded.getBookedSettlementAmount()).isEqualByComparingTo("10500.56");
        assertThat(reloaded.getSettlementRoundingMode()).isEqualTo(RoundingMode.DOWN);
        assertThat(reloaded.getRoundingResidual()).isEqualByComparingTo("0.007");
        assertThat(reloaded.getSettlementCurrency()).isEqualTo("KRW");
        assertThat(reloaded.getCreatedAt()).isEqualTo(created);
        assertThat(reloaded.getCompletedAt()).isEqualTo(created.plusSeconds(2));
        // NUMERIC(20,8) must come back at scale 8 — no precision loss through PG.
        assertThat(reloaded.getBookedSettlementAmount().scale()).isEqualTo(8);
    }

    @Test
    void uncertainAttemptKeepsSettlementSnapshotNull() {
        ExecutionAttemptEntity attempt = new ExecutionAttemptEntity(
                "TXN-PG-002", 42L, "PTN-REF-002", "zeropay",
                PaymentMode.MPM, PaymentStatus.UNCERTAIN, Instant.parse("2026-06-09T11:00:00Z"));
        attempt.setPrefundDeductedUsd(new BigDecimal("100.00000000"));

        Long id = attempts.saveAndFlush(attempt).getId();
        entityManager.clear();

        ExecutionAttemptEntity reloaded = attempts.findById(id).orElseThrow();
        assertThat(reloaded.getOutcome()).isEqualTo(PaymentStatus.UNCERTAIN);
        assertThat(reloaded.getBookedSettlementAmount()).isNull();
        assertThat(reloaded.getSettlementRoundingMode()).isNull();
        assertThat(reloaded.getRoundingResidual()).isNull();
        assertThat(reloaded.getSettlementCurrency()).isNull();
        assertThat(reloaded.getCompletedAt()).isNull();
    }

    @Test
    void queryMethodsOrderAndFilterAttempts() {
        Instant t0 = Instant.parse("2026-06-09T12:00:00Z");
        ExecutionAttemptEntity first = new ExecutionAttemptEntity(
                "TXN-PG-003", 7L, "PTN-REF-003", "zeropay",
                PaymentMode.CPM, PaymentStatus.FAILED, t0);
        first.setFailureReason("scheme declined");
        attempts.save(first);
        attempts.save(new ExecutionAttemptEntity(
                "TXN-PG-003", 7L, "PTN-REF-003", "zeropay",
                PaymentMode.CPM, PaymentStatus.APPROVED, t0.plusSeconds(30)));
        attempts.flush();

        List<ExecutionAttemptEntity> byTxn = attempts.findByTxnRefOrderByCreatedAtAscIdAsc("TXN-PG-003");
        assertThat(byTxn).hasSize(2);
        assertThat(byTxn.get(0).getOutcome()).isEqualTo(PaymentStatus.FAILED);
        assertThat(byTxn.get(0).getFailureReason()).isEqualTo("scheme declined");
        assertThat(byTxn.get(1).getOutcome()).isEqualTo(PaymentStatus.APPROVED);

        Optional<ExecutionAttemptEntity> latest =
                attempts.findFirstByPartnerIdAndPartnerTxnRefOrderByCreatedAtDescIdDesc(7L, "PTN-REF-003");
        assertThat(latest).isPresent();
        assertThat(latest.get().getOutcome()).isEqualTo(PaymentStatus.APPROVED);

        assertThat(attempts.countByOutcome(PaymentStatus.FAILED)).isEqualTo(1);
    }

    @Test
    void paymentModeCheckConstraintEnforcedByRealPostgres() {
        // Bypass the enum-typed entity so the DB-level CHECK is what rejects the row.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO execution_attempts "
                        + "(txn_ref, partner_id, partner_txn_ref, scheme_id, payment_mode, outcome, created_at) "
                        + "VALUES ('TXN-BAD', 1, 'REF-BAD', 'zeropay', 'XXX', 'APPROVED', now())"))
                .isInstanceOf(DataAccessException.class);
    }
}
