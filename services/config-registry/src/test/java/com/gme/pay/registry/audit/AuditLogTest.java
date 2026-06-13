package com.gme.pay.registry.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.audit.AuditEvent;
import com.gme.pay.audit.HashChain;
import com.gme.pay.audit.RecordingAuditPublisher;
import com.gme.pay.domain.Partner;
import com.gme.pay.domain.PartnerType;
import com.gme.pay.registry.cache.CacheConfig;
import com.gme.pay.registry.partner.PartnerStore;
import java.math.RoundingMode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Slice 1B.3 acceptance test for the ADR-007 audit log + hash chain wired
 * end-to-end through {@link PartnerStore#save}.
 *
 * <p>Runs as a {@code @DataJpaTest} slice so Flyway applies V001..V006 against
 * the H2 PostgreSQL-mode test database and the {@code audit_log} table is real.
 * The audit module's collaborators ({@link AuditLogService}, {@link AuditLogRepository}
 * via the {@code @Repository} interface scan) and {@link PartnerStore} itself are
 * imported explicitly because {@code @DataJpaTest} does not bring in
 * {@code @Service} / {@code @Component} beans by default.
 *
 * <h2>What this test pins</h2>
 *
 * <ol>
 *   <li>{@link #partnerStoreSavePublishesAuditEvent} — one row written per
 *       {@code PartnerStore.save}, with BEFORE/AFTER snapshots filled in.</li>
 *   <li>{@link #hashChainValidatesAcrossMultipleSaves} — the chain reads back
 *       intact via {@link HashChain#verify} after several writes.</li>
 *   <li>{@link #tamperingWithAfterJsonbInvalidatesChain} — silently rewriting an
 *       earlier row's {@code afterJsonb} (the way a malicious operator with raw
 *       DB access would attempt) makes verification fail at the tampered row,
 *       which is exactly the regulator-defensible property of the hash chain.</li>
 * </ol>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AuditLogTest.TestConfig.class, AuditLogService.class, PartnerStore.class, CacheConfig.class})
class AuditLogTest {

    @Autowired
    private PartnerStore store;

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private RecordingAuditPublisher publisher;

    /**
     * Test wiring: swap in a {@link RecordingAuditPublisher} as the
     * {@code AuditPublisher} bean so we can assert what was published. Marked
     * {@code @Primary} so it wins over the default {@code LogAuditPublisher}
     * (which is registered by {@link AuditConfig} via
     * {@code @ConditionalOnMissingBean} — we add a Primary alongside; either
     * would suffice but the explicit Primary keeps intent obvious).
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        RecordingAuditPublisher recordingAuditPublisher() {
            return new RecordingAuditPublisher();
        }

        @Bean
        com.gme.pay.audit.AuditPublisher auditPublisher(RecordingAuditPublisher recording) {
            return recording;
        }
    }

    @Test
    void partnerStoreSavePublishesAuditEvent() {
        publisher.clear();

        // Insert a brand-new partner — there is no prior row, so the audit
        // BEFORE column is null and the prev_hash should be the GENESIS vector.
        store.save(Partner.of("AUDIT_NEW", PartnerType.OVERSEAS, "USD", RoundingMode.HALF_UP));

        List<AuditEvent> events = publisher.published();
        assertThat(events)
                .as("PartnerStore.save MUST publish exactly one audit event per write")
                .hasSize(1);

        AuditEvent only = events.get(0);
        assertThat(only.aggregateType()).isEqualTo("partner");
        assertThat(only.aggregateId()).isEqualTo("AUDIT_NEW");
        assertThat(only.eventType()).isEqualTo("PARTNER_SAVED");
        assertThat(only.beforeJsonb())
                .as("a first-write event MUST record BEFORE as null").isNull();
        assertThat(only.afterJsonb()).isNotNull();
        assertThat(new String(only.afterJsonb(), java.nio.charset.StandardCharsets.UTF_8))
                .as("AFTER snapshot is the canonical partner JSON")
                .contains("\"partnerCode\":\"AUDIT_NEW\"")
                .contains("\"type\":\"OVERSEAS\"")
                .contains("\"settlementCurrency\":\"USD\"");
        assertThat(only.prevHash())
                .as("first row of a chain MUST use the GENESIS prev_hash")
                .isEqualTo(HashChain.GENESIS);
        assertThat(only.rowHash())
                .as("row_hash MUST be 32 bytes")
                .hasSize(HashChain.HASH_LEN);

        // The persisted row is queryable through the repository.
        List<AuditLogEntity> persisted = auditLogRepository.findChainByAggregate("partner", "AUDIT_NEW");
        assertThat(persisted).hasSize(1);
        assertThat(persisted.get(0).getRowHash()).isEqualTo(only.rowHash());
    }

    @Test
    void hashChainValidatesAcrossMultipleSaves() {
        publisher.clear();

        // Three writes to the same partner_code build a three-row chain (V004
        // bitemporal storage means each save inserts a new SCD-6 row + supersedes
        // the prior — but the audit chain only cares about the per-aggregate
        // sequence, not the partner table's row count).
        store.save(Partner.of("AUDIT_MULTI", PartnerType.LOCAL, "KRW", RoundingMode.HALF_UP));
        store.save(Partner.of("AUDIT_MULTI", PartnerType.LOCAL, "KRW", RoundingMode.DOWN));
        store.save(Partner.of("AUDIT_MULTI", PartnerType.LOCAL, "KRW", RoundingMode.FLOOR));

        // Three published events, in write order.
        assertThat(publisher.published()).hasSize(3);

        // Verify the chain via the service's verifier (which reads from the DB).
        long firstBad = auditLogService.verifyChain("partner", "AUDIT_MULTI");
        assertThat(firstBad)
                .as("intact chain must verify with no bad rows (sentinel -1)")
                .isEqualTo(-1L);

        // Sanity-check via the lower-level HashChain helper directly.
        List<AuditLogEntity> rows = auditLogRepository.findChainByAggregate("partner", "AUDIT_MULTI");
        List<AuditEvent> asEvents = rows.stream().map(AuditLogEntity::toDomain).toList();
        assertThat(HashChain.verify(asEvents))
                .as("HashChain.verify returns -1 for an intact chain")
                .isEqualTo(-1);

        // The chain must be strictly linked: each row's prevHash equals the
        // predecessor's rowHash. We pin that explicitly because "verify returns -1"
        // is necessary but not sufficient evidence the writer chained correctly
        // (a bug that always used GENESIS as prevHash would still trip verify, but
        // we want to fail fast if the wiring breaks subtly).
        assertThat(asEvents.get(0).prevHash()).isEqualTo(HashChain.GENESIS);
        assertThat(asEvents.get(1).prevHash()).isEqualTo(asEvents.get(0).rowHash());
        assertThat(asEvents.get(2).prevHash()).isEqualTo(asEvents.get(1).rowHash());
    }

    @Test
    void tamperingWithAfterJsonbInvalidatesChain() {
        publisher.clear();

        // Build a five-row chain on a fresh aggregate.
        for (int i = 0; i < 5; i++) {
            RoundingMode mode = switch (i % 5) {
                case 0 -> RoundingMode.HALF_UP;
                case 1 -> RoundingMode.DOWN;
                case 2 -> RoundingMode.FLOOR;
                case 3 -> RoundingMode.CEILING;
                default -> RoundingMode.HALF_EVEN;
            };
            store.save(Partner.of("AUDIT_TAMPER", PartnerType.LOCAL, "KRW", mode));
        }

        List<AuditLogEntity> rows = auditLogRepository.findChainByAggregate("partner", "AUDIT_TAMPER");
        assertThat(rows).hasSize(5);

        // Sanity: untampered chain validates.
        assertThat(auditLogService.verifyChain("partner", "AUDIT_TAMPER"))
                .as("untampered chain MUST validate")
                .isEqualTo(-1L);

        // Tamper: silently rewrite the AFTER snapshot on the middle row. In
        // production the audit_log table would forbid this via DB role (Slice 8
        // hardening); for now we simulate the attacker by using the entity setter
        // directly inside this test only.
        AuditLogEntity middle = rows.get(2);
        long tamperedRowId = middle.getId();
        middle.setAfterJsonb("{\"silently\":\"rewritten\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        auditLogRepository.saveAndFlush(middle);

        // The verifier MUST flag the tampered row. Note the row_hash stored on the
        // tampered row was computed from the ORIGINAL afterJsonb; recomputing it
        // from the rewritten bytes yields a different digest, so verify() detects
        // the drift exactly at the tampered row.
        long firstBad = auditLogService.verifyChain("partner", "AUDIT_TAMPER");
        assertThat(firstBad)
                .as("verifier MUST identify the tampered row by its id")
                .isEqualTo(tamperedRowId);

        // The two rows AFTER the tampered one are themselves byte-perfect, but the
        // verifier returns the FIRST bad row (idx=2 in the chain) — that is the
        // regulator's "where did the chain break" answer. We pin the property so
        // a refactor to "return all bad rows" or "return the last good row"
        // surfaces in this test.
        List<AuditEvent> rebuiltEvents = auditLogRepository
                .findChainByAggregate("partner", "AUDIT_TAMPER").stream()
                .map(AuditLogEntity::toDomain).toList();
        int idx = HashChain.verify(rebuiltEvents);
        assertThat(idx).as("HashChain.verify points to the tampered row at index 2").isEqualTo(2);
    }
}
