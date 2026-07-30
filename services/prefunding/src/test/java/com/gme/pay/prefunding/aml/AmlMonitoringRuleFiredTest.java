package com.gme.pay.prefunding.aml;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.prefunding.audit.PrefundingAuditor;
import com.gme.pay.prefunding.outbox.OutboxEntity;
import com.gme.pay.prefunding.outbox.OutboxRepository;
import com.gme.pay.prefunding.persistence.CumulativeUsageLedgerEntity;
import com.gme.pay.prefunding.persistence.CumulativeUsageLedgerRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Gap T5-3 — what happens once an operator DOES configure a rule.
 *
 * <p>The rules below exist only inside this test's Spring context. They are not shipped, not
 * suggested, and their numbers carry no meaning beyond making the arithmetic checkable: the fixture
 * nets to USD 375 over five days, so a threshold of 300 must fire and a threshold of exactly 375
 * must not. Nothing here should be read as a recommended configuration — see
 * {@link AmlMonitoringRules} for why the shipped list is empty.
 *
 * <p>Four rules are configured to pin four separate properties in one evaluation:
 * <ol>
 *   <li><b>fires</b> — 375 &gt; 300, and the alert carries the rule name, metric, observed value,
 *       threshold and window;</li>
 *   <li><b>equality does not fire</b> — 375 is not strictly greater than 375, matching the
 *       cumulative-cap boundary on the authorize path where usage exactly at the cap is allowed;</li>
 *   <li><b>a rule longer than the window is NOT EVALUATED, and says so</b> — measuring a 90-day rule
 *       against 5 days of data would report a pass it never earned;</li>
 *   <li><b>a trailing sub-window really narrows the measurement</b> — the same USD 100 threshold
 *       that the full window would trip does not trip over the last 2 days, which hold USD 25.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "gmepay.outbox.poll-ms=3600000",
        // (1) fires: 375 > 300
        "gmepay.aml.monitoring.rules[0].name=window-net-usd-over-300",
        "gmepay.aml.monitoring.rules[0].metric=WINDOW_NET_USD",
        "gmepay.aml.monitoring.rules[0].threshold=300",
        // (2) equality must NOT fire
        "gmepay.aml.monitoring.rules[1].name=window-net-usd-over-375",
        "gmepay.aml.monitoring.rules[1].metric=WINDOW_NET_USD",
        "gmepay.aml.monitoring.rules[1].threshold=375",
        // (3) asks for more days than the window holds -> reported as not evaluated
        "gmepay.aml.monitoring.rules[2].name=trailing-90-day-usd",
        "gmepay.aml.monitoring.rules[2].metric=WINDOW_NET_USD",
        "gmepay.aml.monitoring.rules[2].threshold=0",
        "gmepay.aml.monitoring.rules[2].window-days=90",
        // (4) trailing 2 days hold only USD 25, so this does NOT fire even though the full window would
        "gmepay.aml.monitoring.rules[3].name=trailing-2-day-usd-over-100",
        "gmepay.aml.monitoring.rules[3].metric=WINDOW_NET_USD",
        "gmepay.aml.monitoring.rules[3].threshold=100",
        "gmepay.aml.monitoring.rules[3].window-days=2"})
@ActiveProfiles("test")
class AmlMonitoringRuleFiredTest {

    /** Used by the firing / de-duplication test. */
    private static final String PARTNER_A = "AML_FIRE_A";

    /**
     * Used by the "a different window alerts again" test. A separate partner because the
     * de-duplication memory is a process-lifetime singleton and is NOT reset between test methods —
     * sharing a partner would make the two tests order-dependent.
     */
    private static final String PARTNER_B = "AML_FIRE_B";

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
        for (String partner : List.of(PARTNER_A, PARTNER_B)) {
            balances.save(new PartnerBalanceEntity(partner, "USD",
                    new BigDecimal("1000000.00000000"), null, Instant.now()));
            charge(partner, "2026-07-01", "T1", "100");
            charge(partner, "2026-07-01", "T2", "200");
            charge(partner, "2026-07-02", "T3", "500");
            reverse(partner, "2026-07-02", "T3", "500");
            charge(partner, "2026-07-03", "T4", "50");
            charge(partner, "2026-07-05", "T5", "25");
            charge(partner, "2026-07-06", "T6", "777");
        }
    }

    @Test
    @DisplayName("a configured rule that is exceeded fires exactly ONE alert naming the rule, metric, observed value and threshold — and re-evaluating the same window does not double-fire")
    void firesOnceAndDoesNotDoubleFire() {
        AmlWindowEvidence evidence = monitoring.evidence(PARTNER_A, "2026-07-01", "2026-07-05");
        assertThat(evidence.windowNetUsd()).isEqualByComparingTo("375");

        AmlEvaluation first = evaluator.evaluate(evidence);

        assertThat(first.rulesConfigured()).isEqualTo(4);
        assertThat(first.fired())
                .as("only the 300 threshold is strictly exceeded; 375 > 375 is false, and the "
                        + "trailing-2-day window holds only 25")
                .hasSize(1);
        AmlEvaluation.FiredRule fired = first.fired().get(0);
        assertThat(fired.rule()).isEqualTo("window-net-usd-over-300");
        assertThat(fired.metric()).isEqualTo(AmlMetric.WINDOW_NET_USD);
        assertThat(fired.observed()).isEqualByComparingTo("375");
        assertThat(fired.threshold()).isEqualByComparingTo("300");
        assertThat(fired.windowFrom()).isEqualTo("2026-07-01");
        assertThat(fired.windowTo()).isEqualTo("2026-07-05");
        assertThat(fired.windowDays()).isEqualTo(5);
        assertThat(fired.alertRaised()).isTrue();

        assertThat(first.notEvaluated())
                .as("a rule asking for more days than the window holds must be reported, not skipped")
                .hasSize(1);
        assertThat(first.notEvaluated().get(0).rule()).isEqualTo("trailing-90-day-usd");
        assertThat(first.notEvaluated().get(0).reason()).contains("90");

        // ---- exactly one outbox event, carrying the whole determination ----
        List<OutboxEntity> events = outbox.findAll();
        assertThat(events).hasSize(1);
        OutboxEntity event = events.get(0);
        assertThat(event.getEventType())
                .isEqualTo(AmlMonitoringEvaluator.EVENT_TYPE_AML_MONITORING_ALERT);
        assertThat(event.getAggregateId()).isEqualTo(PARTNER_A);
        assertThat(event.getPayload())
                .contains("\"rule\":\"window-net-usd-over-300\"")
                .contains("\"metric\":\"WINDOW_NET_USD\"")
                .contains("\"observed\":\"375")
                .contains("\"threshold\":\"300\"")
                .contains("\"windowFrom\":\"2026-07-01\"")
                .contains("\"windowTo\":\"2026-07-05\"")
                // The alert must not be readable as a completed compliance determination.
                .contains("requires compliance review");

        // ---- exactly one audit row, on the AML usage chain, by a NAMED system principal ----
        List<Map<String, Object>> auditRows = amlAuditRows(PARTNER_A);
        assertThat(auditRows).hasSize(1);
        Map<String, Object> row = auditRows.get(0);
        assertThat(row.get("event_type")).isEqualTo(PrefundingAuditor.AML_MONITORING_RULE_FIRED);
        assertThat(row.get("actor_id")).isEqualTo("system:prefunding-aml-monitoring");
        assertThat(AuditActors.isSystem((String) row.get("actor_id")))
                .as("a threshold comparison is a platform determination, not the reader's action")
                .isTrue();
        assertThat(json(row, "before_jsonb")).isEqualTo(
                "{\"rule\":\"window-net-usd-over-300\",\"metric\":\"WINDOW_NET_USD\","
                        + "\"threshold\":\"300\",\"fired\":false}");
        assertThat(json(row, "after_jsonb"))
                .startsWith("{\"rule\":\"window-net-usd-over-300\",\"metric\":\"WINDOW_NET_USD\","
                        + "\"threshold\":\"300\",\"fired\":true,\"observed\":\"375")
                .endsWith("\"windowFrom\":\"2026-07-01\",\"windowTo\":\"2026-07-05\","
                        + "\"windowDays\":5}");

        // ---- re-evaluating the SAME window must not raise a second alert ----
        AmlEvaluation second = evaluator.evaluate(
                monitoring.evidence(PARTNER_A, "2026-07-01", "2026-07-05"));
        assertThat(second.fired())
                .as("the rule still trips — suppression is reported, not hidden")
                .hasSize(1);
        assertThat(second.fired().get(0).alertRaised())
                .as("but no second alert was raised for the same (partner, rule, window)")
                .isFalse();
        assertThat(outbox.findAll()).hasSize(1);
        assertThat(amlAuditRows(PARTNER_A)).hasSize(1);
    }

    @Test
    @DisplayName("de-duplication is keyed on the WINDOW, not just the partner and rule: a different period alerts again")
    void aDifferentWindowAlertsAgain() {
        // 2026-07-01..03 nets 350 — over the 300 threshold, under the 375 one.
        AmlEvaluation narrow = evaluator.evaluate(
                monitoring.evidence(PARTNER_B, "2026-07-01", "2026-07-03"));
        assertThat(narrow.fired()).hasSize(1);
        assertThat(narrow.fired().get(0).observed()).isEqualByComparingTo("350");
        assertThat(outbox.findAll()).hasSize(1);

        // A genuinely different period. Suppressing it would mean a partner whose behaviour keeps
        // tripping a rule goes quiet after the first alert — the failure mode that matters.
        AmlEvaluation wider = evaluator.evaluate(
                monitoring.evidence(PARTNER_B, "2026-07-01", "2026-07-05"));
        assertThat(wider.fired()).hasSize(1);
        assertThat(wider.fired().get(0).alertRaised()).isTrue();
        assertThat(wider.fired().get(0).observed()).isEqualByComparingTo("375");
        assertThat(outbox.findAll()).hasSize(2);
        assertThat(amlAuditRows(PARTNER_B)).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private List<Map<String, Object>> amlAuditRows(String partner) {
        return jdbc.queryForList(
                "SELECT id, actor_id, event_type, before_jsonb, after_jsonb FROM audit_log "
                        + "WHERE aggregate_type = ? AND aggregate_id = ? ORDER BY id ASC",
                PrefundingAuditor.AGG_AML_USAGE, partner);
    }

    private static String json(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? null : new String((byte[]) value, StandardCharsets.UTF_8);
    }

    private void charge(String partner, String dailyKey, String txnRef, String usd) {
        cumulative.save(new CumulativeUsageLedgerEntity(partner, txnRef, "CUM_CHARGE",
                new BigDecimal(usd), dailyKey, dailyKey.substring(0, 7), dailyKey.substring(0, 4),
                Instant.now()));
    }

    private void reverse(String partner, String dailyKey, String txnRef, String usd) {
        cumulative.save(new CumulativeUsageLedgerEntity(partner, txnRef, "CUM_REVERSE",
                new BigDecimal(usd).negate(), dailyKey, dailyKey.substring(0, 7),
                dailyKey.substring(0, 4), Instant.now()));
    }
}
