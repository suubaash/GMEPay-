package com.gme.pay.prefunding.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.audit.HashChain;
import com.gme.pay.prefunding.persistence.BalanceAlertRepository;
import com.gme.pay.prefunding.persistence.CumulativeUsageLedgerRepository;
import com.gme.pay.prefunding.persistence.LedgerEntryRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import com.gme.pay.prefunding.service.PrefundingService;
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
 * Proves prefunding's audit chain is <b>verifiable</b>, not merely hashed.
 *
 * <p>The distinction matters more than it sounds. Storing {@code prev_hash}/{@code row_hash} does not
 * stop anyone editing {@code audit_log}; it only makes an edit detectable, and only by something that
 * recomputes the digests. If nothing ever recomputes them, "the prefunding audit trail is
 * hash-chained" is an unfalsifiable statement about two columns nobody reads. So the tests below do
 * what an investigator would do: write real float movements, verify, then tamper with a row exactly
 * the way a DBA with UPDATE rights would, and require the verifier to name the offending
 * {@code audit_log.id} and say why.
 *
 * <p>The tamper simulated here — rewriting {@code after_jsonb} — is the realistic one: changing the
 * amount, or the reason, on a movement after the fact. Rewriting {@code actor_id} or {@code actor_ip}
 * would be caught by the same digest (both are sealed from {@link HashChain#CHAIN_V2}); that property
 * belongs to lib-audit and is covered by its own {@code HashChainVersionTest}, so it is not
 * re-asserted here.
 */
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
@ActiveProfiles("test")
class PrefundingAuditChainTest {

    private static final String PARTNER = "AUD_CHAIN";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PrefundingService service;
    @Autowired private AuditChainVerifier verifier;
    @Autowired private PartnerBalanceRepository balances;
    @Autowired private LedgerEntryRepository ledger;
    @Autowired private BalanceAlertRepository alerts;
    @Autowired private CumulativeUsageLedgerRepository cumulative;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM audit_log");
        alerts.deleteAll();
        ledger.deleteAll();
        cumulative.deleteAll();
        balances.deleteAll();
        balances.save(new PartnerBalanceEntity(PARTNER, "USD",
                new BigDecimal("1000.00000000"), new BigDecimal("100.00000000"), Instant.now()));
    }

    /** Five real movements: debit, credit, hold, capture, top-up. */
    private void driveSeveralMovements() {
        service.deduct(PARTNER, "c-1", new BigDecimal("100.00000000"));
        service.credit(PARTNER, new BigDecimal("50.00000000"));
        service.reserve(PARTNER, "c-2", new BigDecimal("30.00000000"));
        service.capture(PARTNER, "c-2");
        service.credit(PARTNER, new BigDecimal("7.00000000"));
    }

    @Test
    @DisplayName("the chain verifies intact after several float movements, and reports zero legacy-digest rows")
    void chainIsIntactAfterSeveralWrites() {
        driveSeveralMovements();

        AuditChainVerifier.ChainReport report =
                verifier.verify(PrefundingAuditor.AGG_BALANCE, PARTNER);

        assertThat(report.intact()).isTrue();
        assertThat(report.rowsChecked()).isEqualTo(5);
        assertThat(report.firstBrokenRowId()).isNull();
        assertThat(report.breakKind()).isNull();
        // V010 creates the table empty, so nothing here can be on the weaker CHAIN_V1 digest. Asserted
        // rather than assumed: a non-zero count would mean something wrote a v1 row, which is itself
        // the finding (v1 leaves aggregate_type / aggregate_id / actor_ip outside the hash).
        assertThat(report.legacyV1Rows()).isZero();
    }

    @Test
    @DisplayName("the whole-table sweep covers every chain and reports none broken")
    void sweepCoversEveryChainAndReportsIntact() {
        driveSeveralMovements();
        service.setCreditLimit(PARTNER, new BigDecimal("250.00"));           // partner_limit chain
        service.chargeCumulative(PARTNER, "c-3", new BigDecimal("9.00"),
                null, null, null, null);                                    // partner_aml_usage chain

        AuditChainVerifier.SweepReport sweep = verifier.verifyAll();

        assertThat(sweep.intact()).isTrue();
        assertThat(sweep.chainsBroken()).isZero();
        assertThat(sweep.rowsChecked()).isEqualTo(7);
        assertThat(sweep.chains())
                .extracting(AuditChainVerifier.ChainReport::aggregateType)
                .containsExactlyInAnyOrder(
                        PrefundingAuditor.AGG_BALANCE,
                        PrefundingAuditor.AGG_LIMIT,
                        PrefundingAuditor.AGG_AML_USAGE);
    }

    @Test
    @DisplayName("editing a movement's after_jsonb in place is caught, and the verifier names the offending audit_log.id")
    void mutatedRowIsCaughtAndNamedById() {
        driveSeveralMovements();
        List<Long> ids = ids();
        assertThat(ids).hasSize(5);
        Long tampered = ids.get(2);   // a middle row: the break must be reported here, not at the head

        // Exactly what a DBA with UPDATE rights would do to make a movement look smaller than it was.
        // Note the stored row_hash is left alone — that is the point: the hash no longer matches the
        // content it is supposed to seal.
        int updated = jdbc.update("UPDATE audit_log SET after_jsonb = ? WHERE id = ?",
                "{\"balance\":\"0.00000000\",\"reserved\":\"0.00000000\",\"creditLimit\":\"0.00000000\","
                        + "\"currency\":\"USD\",\"entryType\":\"RESERVE\",\"amountUsd\":\"0.01000000\","
                        + "\"txnRef\":\"c-2\",\"ledgerEntryId\":1,\"reason\":null}"
                        .getBytes(StandardCharsets.UTF_8),
                tampered);
        assertThat(updated).isEqualTo(1);

        AuditChainVerifier.ChainReport report =
                verifier.verify(PrefundingAuditor.AGG_BALANCE, PARTNER);

        assertThat(report.intact()).isFalse();
        assertThat(report.firstBrokenRowId())
                .as("the report must cite the primary key an investigator can select on, "
                        + "not a position in a list")
                .isEqualTo(tampered);
        assertThat(report.breakKind())
                .isEqualTo(HashChain.BreakKind.ROW_HASH_MISMATCH.name());
        assertThat(report.detail()).contains("MODIFIED after it was written");

        // And the sweep surfaces it rather than averaging it away.
        AuditChainVerifier.SweepReport sweep = verifier.verifyAll();
        assertThat(sweep.intact()).isFalse();
        assertThat(sweep.chainsBroken()).isEqualTo(1);
        assertThat(sweep.chains().get(0).intact())
                .as("broken chains are listed first so a long report cannot bury the finding")
                .isFalse();
    }

    @Test
    @DisplayName("deleting the first row of a chain is caught as a broken link at the new head")
    void deletedLeadingRowIsCaught() {
        driveSeveralMovements();
        List<Long> ids = ids();
        jdbc.update("DELETE FROM audit_log WHERE id = ?", ids.get(0));

        AuditChainVerifier.ChainReport report =
                verifier.verify(PrefundingAuditor.AGG_BALANCE, PARTNER);

        assertThat(report.intact()).isFalse();
        assertThat(report.firstBrokenRowId()).isEqualTo(ids.get(1));
        assertThat(report.breakKind()).isEqualTo(HashChain.BreakKind.PREV_HASH_MISMATCH.name());
        assertThat(report.detail()).contains("DELETED");
    }

    @Test
    @DisplayName("an aggregate with no rows verifies as trivially intact — 'no rows' is not dressed up as a failure")
    void emptyChainIsIntact() {
        AuditChainVerifier.ChainReport report =
                verifier.verify(PrefundingAuditor.AGG_BALANCE, "NO_SUCH_PARTNER");

        assertThat(report.intact()).isTrue();
        assertThat(report.rowsChecked()).isZero();
    }

    private List<Long> ids() {
        return jdbc.queryForList(
                "SELECT id FROM audit_log WHERE aggregate_type = ? AND aggregate_id = ? ORDER BY id ASC",
                Long.class, PrefundingAuditor.AGG_BALANCE, PARTNER);
    }
}
