package com.gme.pay.registry.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.audit.AuditPublisher;
import com.gme.pay.audit.HashChain;
import com.gme.pay.audit.RecordingAuditPublisher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * The tamper-evidence half of gap T5-1 end-to-end against a real {@code audit_log} table: the
 * integrity sweep must pass on a clean chain and, on a deliberately mutated row, name <b>which
 * row id</b> broke and <b>why</b>.
 *
 * <p>Runs as a {@code @DataJpaTest} slice so Flyway applies V001..V044 (including V043's
 * {@code chain_version}) against H2 in PostgreSQL mode. Note that the DB-level append-only guard is
 * PostgreSQL-only — see {@code db/vendor/h2/V044__audit_log_append_only.sql} — which is precisely
 * why the tamper test below is writable here and would not be against production.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AuditIntegrityTest.TestConfig.class, AuditLogService.class, AuditIntegrityService.class})
class AuditIntegrityTest {

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditIntegrityService integrity;

    @Autowired
    private AuditLogRepository repository;

    @PersistenceContext
    private EntityManager em;

    @org.springframework.boot.test.context.TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        AuditPublisher fanout() {
            // A recording (non-DB) fan-out publisher: the DB write is AuditLogService's own job,
            // and a DB publisher here would double-insert — see AuditConfig's javadoc.
            return new RecordingAuditPublisher();
        }
    }

    @Test
    @DisplayName("a clean chain verifies, and the sweep reports zero broken chains")
    void cleanChainVerifies() {
        writeChain("partner", "INTEG_A", 4);
        writeChain("partner_kyb", "INTEG_A", 2);

        AuditIntegrityService.AuditIntegrityReport report = integrity.verifyAll();

        assertThat(report.intact()).isTrue();
        assertThat(report.brokenChains()).isEmpty();
        assertThat(report.chainsChecked()).isGreaterThanOrEqualTo(2);
        assertThat(report.rowsChecked()).isGreaterThanOrEqualTo(6);
    }

    @Test
    @DisplayName("every row written now is sealed under CHAIN_V2, so the legacy count stays at zero")
    void newRowsAreSealedUnderV2() {
        writeChain("partner", "INTEG_V2", 3);

        AuditIntegrityService.ChainResult result = integrity.verifyOne("partner", "INTEG_V2");

        assertThat(result.intact()).isTrue();
        assertThat(result.legacyV1Rows())
                .as("nothing may write a v1 row any more; the count can only shrink from history")
                .isZero();
    }

    @Test
    @DisplayName("an in-place edit of a sealed column is caught, and the offending row id is named")
    void tamperedRowIsNamedById() {
        writeChain("partner", "INTEG_TAMPER", 4);
        Long targetId = repository.findChainByAggregate("partner", "INTEG_TAMPER").get(2).getId();

        // Rewrite the AFTER snapshot of the third row, leaving its stored hashes intact — exactly
        // what an operator with raw DB access would do.
        mutate("UPDATE audit_log SET after_jsonb = ? WHERE id = ?",
                "{\"silently\":\"rewritten\"}".getBytes(StandardCharsets.UTF_8), targetId);

        AuditIntegrityService.ChainResult result =
                integrity.verifyOne("partner", "INTEG_TAMPER");

        assertThat(result.intact()).isFalse();
        assertThat(result.firstBrokenRowId())
                .as("a boolean is not actionable — the report must name the row an investigator "
                        + "should select on")
                .isEqualTo(targetId);
        assertThat(result.breakKind()).isEqualTo(HashChain.BreakKind.ROW_HASH_MISMATCH.name());
        assertThat(result.detail()).contains("MODIFIED");

        // The whole-table sweep surfaces it too, and only it.
        AuditIntegrityService.AuditIntegrityReport report = integrity.verifyAll();
        assertThat(report.intact()).isFalse();
        assertThat(report.brokenChains())
                .extracting(AuditIntegrityService.ChainResult::firstBrokenRowId)
                .containsExactly(targetId);
    }

    @Test
    @DisplayName("rewriting aggregate_id — invisible to the old digest — is now caught")
    void repointingARowIsCaught() {
        writeChain("partner", "INTEG_REPOINT", 2);
        Long targetId = repository.findChainByAggregate("partner", "INTEG_REPOINT").get(1).getId();

        // The CISO finding: aggregate_type / aggregate_id / actor_ip were outside the v1 digest,
        // so this edit used to leave the chain verifying.
        mutate("UPDATE audit_log SET aggregate_id = ? WHERE id = ?", "SOMEONE_ELSE", targetId);

        // The row now belongs to a different (empty-until-now) chain of one, whose stored prev_hash
        // points at a row that is not there — and whose own hash no longer matches its content.
        AuditIntegrityService.AuditIntegrityReport report = integrity.verifyAll();
        assertThat(report.intact()).isFalse();
        assertThat(report.brokenChains()).isNotEmpty();
    }

    @Test
    @DisplayName("the sweep counts rows that are sealed but not attributable to a verified principal")
    void unattributableRowsAreCounted() {
        auditLogService.publish("partner", "INTEG_UNATTR", AuditActors.UNATTRIBUTED, null,
                "PARTNER_SAVED", null, json("{\"v\":1}"));
        auditLogService.publish("partner", "INTEG_UNATTR", AuditActors.unverified("ceo@gme.com"),
                null, "PARTNER_SAVED", json("{\"v\":1}"), json("{\"v\":2}"));
        auditLogService.publish("partner", "INTEG_UNATTR", AuditActors.system("partner-seeder"),
                null, "PARTNER_SAVED", json("{\"v\":2}"), json("{\"v\":3}"));

        AuditIntegrityService.AuditIntegrityReport report = integrity.verifyAll();

        assertThat(report.intact())
                .as("unattributable rows are still correctly SEALED — the two properties are "
                        + "independent, which is the point of reporting them separately")
                .isTrue();
        assertThat(report.unattributableRows())
                .as("the unverified claim and the unattributed row count; the NAMED system "
                        + "principal does not")
                .isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    private void writeChain(String aggregateType, String aggregateId, int rows) {
        byte[] before = null;
        for (int i = 0; i < rows; i++) {
            byte[] after = json("{\"v\":" + i + "}");
            auditLogService.publish(aggregateType, aggregateId,
                    AuditActors.attested("alice@gme.com"), "10.0.0." + i,
                    "PARTNER_SAVED", before, after);
            before = after;
        }
    }

    /**
     * Raw mutation through JDBC. Deliberately bypasses the application: the whole claim under test
     * is that an edit made <i>outside</i> the write path is detected.
     */
    private void mutate(String sql, Object value, Long id) {
        em.flush();
        em.createNativeQuery(sql)
                .setParameter(1, value)
                .setParameter(2, id)
                .executeUpdate();
        em.clear();
    }

    private static byte[] json(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
