package com.gme.pay.ratefx.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.audit.HashChain;
import com.gme.pay.ratefx.issue.RateSnapshotAdminService;
import com.gme.pay.ratefx.persistence.RateSnapshotRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves rate-fx's audit chain is <b>verifiable</b>, not merely hashed.
 *
 * <p>Storing {@code prev_hash}/{@code row_hash} does not stop anyone editing {@code audit_log}; it only
 * makes an edit detectable, and only by something that recomputes the digests. If nothing recomputes
 * them, "the FX rate trail is hash-chained" is an unfalsifiable claim about two columns nobody reads.
 * So these tests do what an investigator would: write real rate changes, verify, then tamper exactly the
 * way someone with UPDATE rights would, and require the verifier to name the offending
 * {@code audit_log.id} and say why.
 *
 * <p>The chain is keyed on the <b>currency</b>, which is what makes the sequence auditable at all —
 * per-snapshot chains would each be one row long and could not detect a rate inserted or removed
 * between two others. The deleted-row test below is the one that depends on that choice.
 */
@SpringBootTest
@ActiveProfiles("test")
class RateAuditChainTest {

    private static final String CCY = "MNT";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private RateSnapshotAdminService adminService;
    @Autowired private RateSnapshotRepository snapshots;
    @Autowired private AuditChainVerifier verifier;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        snapshots.deleteAll();
    }

    /** Four successive manual rates for one currency — a plausible week of treasury activity. */
    private void driveSeveralRateChanges() {
        adminService.record(CCY, new BigDecimal("3400.00"), "MANUAL",
                Instant.parse("2026-07-01T00:00:00Z"));
        adminService.record(CCY, new BigDecimal("3450.00"), "MANUAL",
                Instant.parse("2026-07-02T00:00:00Z"));
        adminService.record(CCY, new BigDecimal("3500.00"), "PARTNER",
                Instant.parse("2026-07-03T00:00:00Z"));
        adminService.record(CCY, new BigDecimal("3900.00"), "MANUAL",
                Instant.parse("2026-07-04T00:00:00Z"));
    }

    @Test
    @DisplayName("the chain verifies intact after several rate changes, and reports zero legacy-digest rows")
    void chainIsIntactAfterSeveralWrites() {
        driveSeveralRateChanges();

        AuditChainVerifier.ChainReport report =
                verifier.verify(RateAuditor.AGG_RATE_SNAPSHOT, CCY);

        assertThat(report.intact()).isTrue();
        assertThat(report.rowsChecked()).isEqualTo(4);
        assertThat(report.firstBrokenRowId()).isNull();
        assertThat(report.breakKind()).isNull();
        // V003 creates the table empty, so nothing can be on the weaker CHAIN_V1 digest (which leaves
        // aggregate_type / aggregate_id / actor_ip outside the hash). Asserted, not assumed: a non-zero
        // count would mean something wrote a v1 row, which is itself the finding.
        assertThat(report.legacyV1Rows()).isZero();
    }

    @Test
    @DisplayName("the whole-table sweep covers every currency chain and reports none broken")
    void sweepCoversEveryChain() {
        driveSeveralRateChanges();
        adminService.record("NPR", new BigDecimal("133.50"), "MANUAL",
                Instant.parse("2026-07-01T00:00:00Z"));

        AuditChainVerifier.SweepReport sweep = verifier.verifyAll();

        assertThat(sweep.intact()).isTrue();
        assertThat(sweep.chainsChecked()).isEqualTo(2);
        assertThat(sweep.rowsChecked()).isEqualTo(5);
        assertThat(sweep.chains())
                .extracting(AuditChainVerifier.ChainReport::aggregateId)
                .containsExactlyInAnyOrder(CCY, "NPR");
    }

    @Test
    @DisplayName("editing a rate change's after_jsonb in place is caught, and the verifier names the offending audit_log.id")
    void mutatedRowIsCaughtAndNamedById() {
        driveSeveralRateChanges();
        List<Long> ids = ids();
        assertThat(ids).hasSize(4);
        Long tampered = ids.get(1);   // a middle row: the break must be reported here, not at the head

        // What someone with UPDATE rights would do to make a rate move look smaller than it was. The
        // stored row_hash is deliberately left alone — that is the point: it no longer matches the
        // content it is supposed to seal.
        int updated = jdbc.update("UPDATE audit_log SET after_jsonb = ? WHERE id = ?",
                ("{\"usdRate\":\"3405.00\",\"source\":\"MANUAL\",\"snapshotId\":\"manual-MNT-forged\","
                        + "\"effectiveAt\":\"2026-07-02T00:00:00Z\",\"reason\":null}")
                        .getBytes(StandardCharsets.UTF_8),
                tampered);
        assertThat(updated).isEqualTo(1);

        AuditChainVerifier.ChainReport report =
                verifier.verify(RateAuditor.AGG_RATE_SNAPSHOT, CCY);

        assertThat(report.intact()).isFalse();
        assertThat(report.firstBrokenRowId())
                .as("the report must cite the primary key an investigator can select on, "
                        + "not a position in a list")
                .isEqualTo(tampered);
        assertThat(report.breakKind()).isEqualTo(HashChain.BreakKind.ROW_HASH_MISMATCH.name());
        assertThat(report.detail()).contains("MODIFIED after it was written");

        AuditChainVerifier.SweepReport sweep = verifier.verifyAll();
        assertThat(sweep.intact()).isFalse();
        assertThat(sweep.chainsBroken()).isEqualTo(1);
        assertThat(sweep.chains().get(0).intact())
                .as("broken chains are listed first so a long report cannot bury the finding")
                .isFalse();
    }

    @Test
    @DisplayName("rewriting the operator on a manual override is caught — actor_id is inside the v2 digest")
    void rewrittenActorIsCaught() {
        driveSeveralRateChanges();
        Long target = ids().get(3);

        // The specific cover-up this gap is about: leave the rate alone, change who set it.
        jdbc.update("UPDATE audit_log SET actor_id = ? WHERE id = ?", "bob@gme.com", target);

        AuditChainVerifier.ChainReport report =
                verifier.verify(RateAuditor.AGG_RATE_SNAPSHOT, CCY);

        assertThat(report.intact()).isFalse();
        assertThat(report.firstBrokenRowId()).isEqualTo(target);
        assertThat(report.breakKind()).isEqualTo(HashChain.BreakKind.ROW_HASH_MISMATCH.name());
    }

    @Test
    @DisplayName("deleting a rate change from the middle of a currency's history is caught as a broken link")
    void deletedMiddleRowIsCaught() {
        driveSeveralRateChanges();
        List<Long> ids = ids();
        jdbc.update("DELETE FROM audit_log WHERE id = ?", ids.get(1));

        AuditChainVerifier.ChainReport report =
                verifier.verify(RateAuditor.AGG_RATE_SNAPSHOT, CCY);

        assertThat(report.intact()).isFalse();
        // The row that now follows the hole is where the linkage fails.
        assertThat(report.firstBrokenRowId()).isEqualTo(ids.get(2));
        assertThat(report.breakKind()).isEqualTo(HashChain.BreakKind.PREV_HASH_MISMATCH.name());
        assertThat(report.detail()).contains("inserted, deleted or reordered");
    }

    @Test
    @DisplayName("a currency with no rate history verifies as trivially intact — 'no rows' is not dressed up as a failure")
    void emptyChainIsIntact() {
        AuditChainVerifier.ChainReport report =
                verifier.verify(RateAuditor.AGG_RATE_SNAPSHOT, "XXX");

        assertThat(report.intact()).isTrue();
        assertThat(report.rowsChecked()).isZero();
    }

    private List<Long> ids() {
        return jdbc.queryForList(
                "SELECT id FROM audit_log WHERE aggregate_type = ? AND aggregate_id = ? ORDER BY id ASC",
                Long.class, RateAuditor.AGG_RATE_SNAPSHOT, CCY);
    }
}
