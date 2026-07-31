package com.gme.pay.prefunding.aml;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Gap T5-3 — <b>a malformed AML monitoring rule fails the service at startup; it is never skipped.</b>
 *
 * <p>The reasoning is the same one that keeps the rule list empty. A rule that is present in the
 * configuration and silently does nothing is worse than no rule at all: somebody wrote it, somebody
 * believes it is running, and it will be counted as coverage in a control inventory while covering
 * nothing. A blank name, a missing metric, a missing or negative threshold or a duplicated name are
 * therefore refusals to start — a typo in a threshold has to be a failed deployment, not a silent
 * hole.
 *
 * <p>Bound through {@link ApplicationContextRunner} rather than a full {@code @SpringBootTest}
 * because what is under test is the BINDING and its validation, and a context that must fail to
 * start is not something a shared application context can demonstrate.
 */
class AmlMonitoringRulesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BindRules.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AmlMonitoringRules.class)
    static class BindRules { }

    // -------------------------------------------------------------------------
    // the shipped default
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("with nothing configured the list binds EMPTY and the context starts — no invented thresholds")
    void defaultsBindEmpty() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            AmlMonitoringRules rules = context.getBean(AmlMonitoringRules.class);
            assertThat(rules.getRules()).isEmpty();
            assertThat(rules.getMaxWindowDays())
                    .isEqualTo(AmlMonitoringRules.DEFAULT_MAX_WINDOW_DAYS);
        });
    }

    @Test
    @DisplayName("a well-formed rule binds, including the enum metric and the optional trailing window")
    void wellFormedRuleBinds() {
        runner.withPropertyValues(
                        "gmepay.aml.monitoring.rules[0].name=a-rule",
                        "gmepay.aml.monitoring.rules[0].metric=MAX_DAILY_NET_TXN_COUNT",
                        "gmepay.aml.monitoring.rules[0].threshold=12",
                        "gmepay.aml.monitoring.rules[0].window-days=7")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AmlMonitoringRules.Rule rule =
                            context.getBean(AmlMonitoringRules.class).getRules().get(0);
                    assertThat(rule.getName()).isEqualTo("a-rule");
                    assertThat(rule.getMetric()).isEqualTo(AmlMetric.MAX_DAILY_NET_TXN_COUNT);
                    assertThat(rule.getThreshold()).isEqualByComparingTo("12");
                    assertThat(rule.getWindowDays()).isEqualTo(7);
                });
    }

    @Test
    @DisplayName("a threshold of zero is legal — 'any activity at all' is a coherent rule, unlike a negative threshold")
    void zeroThresholdIsLegal() {
        runner.withPropertyValues(
                        "gmepay.aml.monitoring.rules[0].name=any-activity",
                        "gmepay.aml.monitoring.rules[0].metric=WINDOW_NET_TXN_COUNT",
                        "gmepay.aml.monitoring.rules[0].threshold=0")
                .run(context -> assertThat(context).hasNotFailed());
    }

    // -------------------------------------------------------------------------
    // malformed rules are refusals to start
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a BLANK name fails startup — an alert nobody can trace back to a rule is not actionable")
    void blankNameFailsStartup() {
        runner.withPropertyValues(
                        "gmepay.aml.monitoring.rules[0].name=",
                        "gmepay.aml.monitoring.rules[0].metric=WINDOW_NET_USD",
                        "gmepay.aml.monitoring.rules[0].threshold=10")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("name is required");
                });
    }

    @Test
    @DisplayName("a MISSING metric fails startup rather than the rule being skipped")
    void missingMetricFailsStartup() {
        runner.withPropertyValues(
                        "gmepay.aml.monitoring.rules[0].name=no-metric",
                        "gmepay.aml.monitoring.rules[0].threshold=10")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("metric is required");
                });
    }

    @Test
    @DisplayName("a MISSING threshold fails startup — a rule that cannot fire must not be configurable")
    void missingThresholdFailsStartup() {
        runner.withPropertyValues(
                        "gmepay.aml.monitoring.rules[0].name=no-threshold",
                        "gmepay.aml.monitoring.rules[0].metric=WINDOW_NET_USD")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("threshold is required");
                });
    }

    @Test
    @DisplayName("a NEGATIVE threshold fails startup — it would fire on every evaluation forever")
    void negativeThresholdFailsStartup() {
        runner.withPropertyValues(
                        "gmepay.aml.monitoring.rules[0].name=negative",
                        "gmepay.aml.monitoring.rules[0].metric=WINDOW_NET_USD",
                        "gmepay.aml.monitoring.rules[0].threshold=-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("threshold must be >= 0");
                });
    }

    @Test
    @DisplayName("a DUPLICATE rule name fails startup — two rules sharing a name would suppress each other's alerts")
    void duplicateNameFailsStartup() {
        runner.withPropertyValues(
                        "gmepay.aml.monitoring.rules[0].name=same",
                        "gmepay.aml.monitoring.rules[0].metric=WINDOW_NET_USD",
                        "gmepay.aml.monitoring.rules[0].threshold=10",
                        "gmepay.aml.monitoring.rules[1].name=SAME",
                        "gmepay.aml.monitoring.rules[1].metric=WINDOW_NET_TXN_COUNT",
                        "gmepay.aml.monitoring.rules[1].threshold=5")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().rootCause().hasMessageContaining("duplicates");
                });
    }

    @Test
    @DisplayName("an UNKNOWN metric fails startup rather than binding to nothing")
    void unknownMetricFailsStartup() {
        runner.withPropertyValues(
                        "gmepay.aml.monitoring.rules[0].name=bad-metric",
                        "gmepay.aml.monitoring.rules[0].metric=SUSPICIOUSNESS",
                        "gmepay.aml.monitoring.rules[0].threshold=10")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("a window-days longer than max-window-days fails startup — the rule could never be evaluated")
    void windowLongerThanTheReadableMaximumFailsStartup() {
        runner.withPropertyValues(
                        "gmepay.aml.monitoring.max-window-days=30",
                        "gmepay.aml.monitoring.rules[0].name=too-long",
                        "gmepay.aml.monitoring.rules[0].metric=WINDOW_NET_USD",
                        "gmepay.aml.monitoring.rules[0].threshold=10",
                        "gmepay.aml.monitoring.rules[0].window-days=90")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("max-window-days");
                });
    }

    @Test
    @DisplayName("a non-positive window-days fails startup")
    void nonPositiveWindowDaysFailsStartup() {
        runner.withPropertyValues(
                        "gmepay.aml.monitoring.rules[0].name=zero-window",
                        "gmepay.aml.monitoring.rules[0].metric=WINDOW_NET_USD",
                        "gmepay.aml.monitoring.rules[0].threshold=10",
                        "gmepay.aml.monitoring.rules[0].window-days=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context).getFailure().rootCause()
                            .hasMessageContaining("window-days must be >= 1");
                });
    }
}
