package com.gme.pay.payment.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-PostgreSQL integration test for {@code idempotency_keys} (ticket 17.2-G08).
 *
 * <p>Verifies the repository round-trip and — critically — that the per-partner unique
 * constraint {@code uq_idempotency_partner_key} is enforced by the real engine, since
 * idempotency correctness relies on the DB rejecting concurrent duplicates.
 *
 * <p>Docker-only: excluded from the normal {@code test} task by the {@code docker} tag and
 * executed by {@code integrationTest} on CI ubuntu runners. Never run locally (no Docker).
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IdempotencyPostgresIT {

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
    private IdempotencyRecordRepository records;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void recordRoundTripsWithOutcomeSnapshot() {
        Instant created = Instant.parse("2026-06-09T09:00:00Z");
        IdempotencyRecordEntity record = new IdempotencyRecordEntity(
                42L, "idem-key-001", "a1b2c3d4e5f6", created);
        record.setTxnRef("TXN-IDEM-001");
        // Money inside the snapshot is a decimal STRING per MONEY_CONVENTION.md.
        record.recordOutcome(PaymentStatus.APPROVED,
                "{\"paymentId\":\"PAY-001\",\"targetPayout\":\"10500.56\",\"payoutCurrency\":\"KRW\"}");
        record.setExpiresAt(created.plusSeconds(86_400));

        Long id = records.saveAndFlush(record).getId();
        entityManager.clear();

        IdempotencyRecordEntity reloaded = records.findById(id).orElseThrow();
        assertThat(reloaded.getPartnerId()).isEqualTo(42L);
        assertThat(reloaded.getIdempotencyKey()).isEqualTo("idem-key-001");
        assertThat(reloaded.getRequestHash()).isEqualTo("a1b2c3d4e5f6");
        assertThat(reloaded.getTxnRef()).isEqualTo("TXN-IDEM-001");
        assertThat(reloaded.getResponseStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(reloaded.getResponseBody()).contains("\"targetPayout\":\"10500.56\"");
        assertThat(reloaded.getCreatedAt()).isEqualTo(created);
        assertThat(reloaded.getExpiresAt()).isEqualTo(created.plusSeconds(86_400));

        Optional<IdempotencyRecordEntity> byKey =
                records.findByPartnerIdAndIdempotencyKey(42L, "idem-key-001");
        assertThat(byKey).isPresent();
        assertThat(byKey.get().getId()).isEqualTo(id);
        assertThat(records.existsByPartnerIdAndIdempotencyKey(42L, "idem-key-001")).isTrue();
        assertThat(records.existsByPartnerIdAndIdempotencyKey(42L, "other-key")).isFalse();
    }

    @Test
    void duplicateKeyForSamePartnerRejectedByUniqueConstraint() {
        Instant now = Instant.parse("2026-06-09T09:30:00Z");
        records.saveAndFlush(new IdempotencyRecordEntity(7L, "dup-key", "hash-1", now));

        assertThatThrownBy(() -> records.saveAndFlush(
                new IdempotencyRecordEntity(7L, "dup-key", "hash-2", now.plusSeconds(1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameKeyDifferentPartnersBothAccepted() {
        Instant now = Instant.parse("2026-06-09T09:45:00Z");
        records.saveAndFlush(new IdempotencyRecordEntity(1L, "shared-key", "hash-p1", now));
        records.saveAndFlush(new IdempotencyRecordEntity(2L, "shared-key", "hash-p2", now));

        assertThat(records.findByPartnerIdAndIdempotencyKey(1L, "shared-key")).isPresent();
        assertThat(records.findByPartnerIdAndIdempotencyKey(2L, "shared-key")).isPresent();
    }

    @Test
    void expiredKeysAreDeletedByHousekeepingQuery() {
        Instant cutoff = Instant.parse("2026-06-09T00:00:00Z");
        IdempotencyRecordEntity expired = new IdempotencyRecordEntity(
                9L, "expired-key", "hash-old", cutoff.minusSeconds(172_800));
        expired.setExpiresAt(cutoff.minusSeconds(86_400));
        records.save(expired);

        IdempotencyRecordEntity live = new IdempotencyRecordEntity(
                9L, "live-key", "hash-new", cutoff.minusSeconds(3_600));
        live.setExpiresAt(cutoff.plusSeconds(86_400));
        records.save(live);
        records.flush();

        long deleted = records.deleteByExpiresAtBefore(cutoff);

        assertThat(deleted).isEqualTo(1);
        assertThat(records.existsByPartnerIdAndIdempotencyKey(9L, "expired-key")).isFalse();
        assertThat(records.existsByPartnerIdAndIdempotencyKey(9L, "live-key")).isTrue();
    }
}
