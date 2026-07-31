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
    private RevenuePostingFailureRepository postingFailures;

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

    /**
     * T2-1: a revenue posting that never reached revenue-ledger must be durable and replayable.
     * Proves the V005 migration is H2-compatible, the payload survives the round-trip, and repeated
     * failures for the same posting bump {@code attempts} on the SAME row (so the PENDING set stays
     * exactly "postings still missing from revenue-ledger").
     */
    @Test
    void revenuePostingFailureIsPersistedAndReplayableAndDeduplicated() {
        RevenuePostingFailureStore store = new RevenuePostingFailureStore(postingFailures, null);

        store.record("TXN-SMN-1", RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE,
                new java.util.LinkedHashMap<>(java.util.Map.of(
                        "txnRef", "TXN-SMN-1",
                        "payoutMarginUsd", new BigDecimal("0.1481"),
                        "serviceChargeAmount", new BigDecimal("500"),
                        "serviceChargeCcy", "KRW")),
                "revenue-ledger 503");
        entityManager.flush();
        entityManager.clear();

        RevenuePostingFailureEntity row = postingFailures
                .findByReferenceAndPostingType("TXN-SMN-1",
                        RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE)
                .orElseThrow();
        assertThat(row.getStatus()).isEqualTo(RevenuePostingFailureEntity.STATUS_PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).contains("503");
        // The payload alone must be enough to replay the POST.
        assertThat(row.getPayload())
                .contains("\"txnRef\":\"TXN-SMN-1\"")
                .contains("0.1481")
                .contains("\"serviceChargeCcy\":\"KRW\"");

        // A second failure of the SAME posting updates the row rather than creating a duplicate.
        store.record("TXN-SMN-1", RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE,
                java.util.Map.of("txnRef", "TXN-SMN-1"), "revenue-ledger still down");
        entityManager.flush();
        entityManager.clear();

        assertThat(postingFailures.findAll()).hasSize(1);
        assertThat(postingFailures
                .findByReferenceAndPostingType("TXN-SMN-1",
                        RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE)
                .orElseThrow()
                .getAttempts()).isEqualTo(2);

        // The replay job's working set.
        assertThat(postingFailures.findByStatusOrderByCreatedAtAscIdAsc(
                RevenuePostingFailureEntity.STATUS_PENDING)).hasSize(1);
    }

    /** Different posting types for one reference are distinct replayable rows. */
    @Test
    void revenuePostingFailuresAreKeyedByReferenceAndType() {
        RevenuePostingFailureStore store = new RevenuePostingFailureStore(postingFailures, null);

        store.record("TXN-MIX-1", RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE,
                java.util.Map.of("txnRef", "TXN-MIX-1"), "boom");
        store.record("TXN-MIX-1", RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL,
                java.util.Map.of("reference", "TXN-MIX-1"), "boom");
        entityManager.flush();

        assertThat(postingFailures.findAll()).hasSize(2);
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
