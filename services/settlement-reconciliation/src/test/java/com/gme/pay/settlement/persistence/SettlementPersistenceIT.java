package com.gme.pay.settlement.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE;

/**
 * Integration test verifying that settlement_batches and settlement_lines persist correctly
 * through the JPA repositories, against the H2 (PostgreSQL mode) DB configured in
 * application.yml and migrated by Flyway.
 *
 * <p>Asserts:
 * <ol>
 *   <li>a batch can be created and saved with its associated lines;</li>
 *   <li>reading back the lines preserves the {@code matched} boolean flag for each row.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = NONE)
class SettlementPersistenceIT {

    @Autowired
    private SettlementBatchRepository batchRepository;

    @Autowired
    private SettlementLineRepository lineRepository;

    @Test
    void createAndSaveBatchWithLines_roundTrip() {
        SettlementBatchEntity batch = new SettlementBatchEntity(
                "BATCH-2026-06-09-LOCAL",
                "GME_REMIT",
                LocalDate.of(2026, 6, 9),
                "PENDING",
                new BigDecimal("100000.00000000"),
                "KRW",
                Instant.parse("2026-06-09T02:00:00Z"));
        batchRepository.save(batch);

        SettlementLineEntity l1 = new SettlementLineEntity(
                batch.getBatchId(), "TXN-001", new BigDecimal("60000.00000000"), "KRW", false);
        SettlementLineEntity l2 = new SettlementLineEntity(
                batch.getBatchId(), "TXN-002", new BigDecimal("40000.00000000"), "KRW", false);
        lineRepository.save(l1);
        lineRepository.save(l2);

        SettlementBatchEntity reloaded = batchRepository.findById("BATCH-2026-06-09-LOCAL").orElseThrow();
        assertThat(reloaded.getPartnerId()).isEqualTo("GME_REMIT");
        assertThat(reloaded.getBusinessDate()).isEqualTo(LocalDate.of(2026, 6, 9));
        assertThat(reloaded.getStatus()).isEqualTo("PENDING");
        assertThat(reloaded.getTotalAmount()).isEqualByComparingTo(new BigDecimal("100000.00000000"));
        assertThat(reloaded.getTotalCurrency()).isEqualTo("KRW");

        List<SettlementLineEntity> lines = lineRepository.findByBatchId(batch.getBatchId());
        assertThat(lines).hasSize(2);
        assertThat(lines).extracting(SettlementLineEntity::getTxnRef)
                .containsExactlyInAnyOrder("TXN-001", "TXN-002");
        assertThat(lines).allSatisfy(l -> {
            assertThat(l.getCurrency()).isEqualTo("KRW");
            assertThat(l.isMatched()).isFalse();
        });
    }

    @Test
    void readBackPreservesMatchedFlag() {
        SettlementBatchEntity batch = new SettlementBatchEntity(
                "BATCH-MATCHED-CHECK",
                "SENDMN",
                LocalDate.of(2026, 6, 9),
                "GENERATED",
                new BigDecimal("50000.00000000"),
                "KRW",
                Instant.parse("2026-06-09T02:30:00Z"));
        batchRepository.save(batch);

        SettlementLineEntity matchedLine = new SettlementLineEntity(
                batch.getBatchId(), "TXN-100", new BigDecimal("30000.00000000"), "KRW", true);
        SettlementLineEntity unmatchedLine = new SettlementLineEntity(
                batch.getBatchId(), "TXN-101", new BigDecimal("20000.00000000"), "KRW", false);
        lineRepository.save(matchedLine);
        lineRepository.save(unmatchedLine);

        lineRepository.flush();

        List<SettlementLineEntity> matched = lineRepository.findByBatchIdAndMatched(batch.getBatchId(), true);
        List<SettlementLineEntity> unmatched = lineRepository.findByBatchIdAndMatched(batch.getBatchId(), false);

        assertThat(matched).hasSize(1);
        assertThat(matched.get(0).getTxnRef()).isEqualTo("TXN-100");
        assertThat(matched.get(0).isMatched()).isTrue();

        assertThat(unmatched).hasSize(1);
        assertThat(unmatched.get(0).getTxnRef()).isEqualTo("TXN-101");
        assertThat(unmatched.get(0).isMatched()).isFalse();
    }
}
