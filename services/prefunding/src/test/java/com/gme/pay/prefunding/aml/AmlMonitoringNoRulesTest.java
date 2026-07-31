package com.gme.pay.prefunding.aml;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.prefunding.outbox.OutboxRepository;
import com.gme.pay.prefunding.persistence.CumulativeUsageLedgerEntity;
import com.gme.pay.prefunding.persistence.CumulativeUsageLedgerRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Gap T5-3 — <b>the shipped default: no rules, therefore no alerts, therefore no I/O.</b>
 *
 * <p>This is the single most important property of the AML monitoring slice, and it is asserted here
 * rather than left to a reading of the configuration. An AML monitoring threshold is a compliance
 * policy input. Nobody on this repository may invent one, so {@code gmepay.aml.monitoring.rules}
 * ships EMPTY — and the honest consequence of that is that prefunding raises <b>no AML monitoring
 * alerts at all</b> until compliance populates it.
 *
 * <p>The failure mode this test exists to prevent is the opposite of a bug: it is a piece of code
 * that <i>looks</i> like a control. A default rule set with plausible-looking numbers would appear in
 * a control inventory and in a due-diligence answer as "GMEPay+ monitors partner transaction volume
 * for structuring", when in truth it would be a figure somebody guessed to make a test pass. So the
 * assertions below pin the absence: zero rules configured, zero outbox events, zero audit rows, and
 * an evaluation result that reports {@code rulesConfigured = 0} rather than a reassuring empty pass.
 *
 * <p>The seeded activity is deliberately enormous. If ANY threshold were hiding in the code, a single
 * day of USD 10,000,000 across 3 transactions would trip it.
 */
@SpringBootTest(properties = "gmepay.outbox.poll-ms=3600000")
@ActiveProfiles("test")
class AmlMonitoringNoRulesTest {

    private static final String PARTNER = "AML_NR_P1";

    @Autowired private AmlMonitoringRules rules;
    @Autowired private AmlMonitoringService monitoring;
    @Autowired private AmlMonitoringEvaluator evaluator;
    @Autowired private CumulativeUsageLedgerRepository cumulative;
    @Autowired private PartnerBalanceRepository balances;
    @Autowired private OutboxRepository outbox;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM audit_log");
        outbox.deleteAll();
        cumulative.deleteAll();
        balances.deleteAll();
        balances.save(new PartnerBalanceEntity(PARTNER, "USD",
                new BigDecimal("1000000.00000000"), null, Instant.now()));

        // Volume and velocity far beyond anything a real rule would tolerate.
        charge("2026-07-01", "BIG-1", "5000000");
        charge("2026-07-01", "BIG-2", "3000000");
        charge("2026-07-01", "BIG-3", "2000000");
    }

    @Test
    @DisplayName("the rule list ships EMPTY — no default thresholds are compiled in or configured")
    void rulesShipEmpty() {
        assertThat(rules.getRules())
                .as("a default AML threshold would be a compliance policy invented by an engineer")
                .isEmpty();
        assertThat(rules.getMaxWindowDays())
                .as("the window bound is a resource limit and does have a default")
                .isEqualTo(AmlMonitoringRules.DEFAULT_MAX_WINDOW_DAYS);
    }

    @Test
    @DisplayName("with no rules, evaluation raises NOTHING — no outbox row, no audit row, and it says nothing was checked")
    void evaluationWithNoRulesRaisesNothing() {
        AmlWindowEvidence evidence = monitoring.evidence(PARTNER, "2026-07-01", "2026-07-31");
        // The evidence itself is real and large — the absence below is not an absence of activity.
        assertThat(evidence.windowNetUsd()).isEqualByComparingTo("10000000");
        assertThat(evidence.windowNetTxnCount()).isEqualTo(3);

        AmlEvaluation result = evaluator.evaluate(evidence);

        assertThat(result.rulesConfigured())
                .as("the result must say NOTHING WAS CHECKED, not merely that nothing tripped")
                .isZero();
        assertThat(result.fired()).isEmpty();
        assertThat(result.notEvaluated()).isEmpty();

        assertThat(outbox.findAll())
                .as("an empty rule list must not produce an alert event")
                .isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class))
                .as("an empty rule list must not produce an audit row")
                .isZero();
    }

    @Test
    @DisplayName("evaluating the same window repeatedly with no rules is still nothing (no accumulating state)")
    void repeatedEvaluationStaysSilent() {
        AmlWindowEvidence evidence = monitoring.evidence(PARTNER, "2026-07-01", "2026-07-31");
        for (int i = 0; i < 3; i++) {
            assertThat(evaluator.evaluate(evidence).fired()).isEmpty();
        }
        assertThat(outbox.findAll()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class)).isZero();
    }

    private void charge(String dailyKey, String txnRef, String usd) {
        cumulative.save(new CumulativeUsageLedgerEntity(PARTNER, txnRef, "CUM_CHARGE",
                new BigDecimal(usd), dailyKey, dailyKey.substring(0, 7), dailyKey.substring(0, 4),
                Instant.now()));
    }
}
