package com.gme.pay.prefunding.aml;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The AML monitoring rules prefunding evaluates against a partner's window evidence — gap T5-3.
 * Bound from {@code gmepay.aml.monitoring.*}.
 *
 * <h2>THE RULE LIST IS EMPTY AND STAYS EMPTY UNTIL COMPLIANCE POPULATES IT</h2>
 *
 * <p>Read this paragraph before adding a rule to any properties file. A monitoring threshold — "USD
 * 50,000 in 7 days", "20 transactions in a day", any number at all — is a <b>COMPLIANCE POLICY
 * INPUT</b>. It encodes a firm's risk appetite, its licence conditions, its regulator's expectations
 * and its customer base. No engineer on this repository is in a position to invent one, and a
 * plausible-looking default committed here would be worse than nothing: it would appear in a control
 * inventory, in a due-diligence questionnaire and in an audit as evidence that GMEPay+ monitors for
 * structuring, when in fact it would be a number somebody made up to make a test pass. That is the
 * exact failure this gap exists to remove, so the defaults are not "sensible starting points" — they
 * are <b>absent</b>.
 *
 * <p>The consequence is stated plainly, because it must not be discovered later: <b>while
 * {@code gmepay.aml.monitoring.rules} is empty, prefunding raises NO AML monitoring alerts at
 * all.</b> {@link AmlMonitoringEvaluator} short-circuits, writes no outbox event, writes no audit
 * row, and touches no I/O. The read surface
 * ({@link AmlMonitoringController}) still returns the full evidence, because evidence is useful with
 * or without rules — but nothing is being watched automatically, and no document should say it is.
 *
 * <h2>Malformed rules fail the service at startup</h2>
 *
 * <p>{@link #afterPropertiesSet()} rejects a rule with a blank name, a missing metric, a missing or
 * negative threshold, a non-positive or over-long {@code windowDays}, or a name that duplicates
 * another rule's. It refuses to start rather than skipping the offender, because a rule that is
 * present in the configuration and silently does nothing is indistinguishable, from the outside,
 * from having no rule at all — and it is worse, because somebody believes it is running. A typo in a
 * threshold must be a failed deployment, not a silent gap in coverage.
 *
 * <h2>Firing condition</h2>
 *
 * <p>A rule fires when the observed metric is <b>strictly greater than</b> the threshold. Equality
 * does not fire, so a threshold reads as "up to and including this is fine" — the same boundary
 * convention as the cumulative caps on the authorize path, where usage exactly at the cap is allowed.
 *
 * <h2>Example (illustrative shape only — these numbers are NOT a recommendation)</h2>
 *
 * <pre>
 *   gmepay.aml.monitoring.rules[0].name=&lt;a name compliance chose&gt;
 *   gmepay.aml.monitoring.rules[0].metric=WINDOW_NET_USD
 *   gmepay.aml.monitoring.rules[0].threshold=&lt;a figure compliance chose&gt;
 *   gmepay.aml.monitoring.rules[0].window-days=&lt;a period compliance chose&gt;
 * </pre>
 */
@ConfigurationProperties(prefix = "gmepay.aml.monitoring")
public class AmlMonitoringRules implements InitializingBean {

    /**
     * Default ceiling on how many KST days a single evidence read may span: one leap year. Chosen
     * as a RESOURCE bound, not a policy one — it is the longest window a regulator-facing look-back
     * plausibly needs, and it stops the read surface being turned into a full-table scan of an
     * append-only ledger by an unbounded date range.
     */
    public static final int DEFAULT_MAX_WINDOW_DAYS = 366;

    private int maxWindowDays = DEFAULT_MAX_WINDOW_DAYS;

    /**
     * The configured rules. <b>Empty by default and intentionally so</b> — see the class javadoc.
     * An empty list means prefunding raises no AML monitoring alerts.
     */
    private List<Rule> rules = new ArrayList<>();

    public int getMaxWindowDays() {
        return maxWindowDays;
    }

    public void setMaxWindowDays(int maxWindowDays) {
        this.maxWindowDays = maxWindowDays;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = rules == null ? new ArrayList<>() : rules;
    }

    /**
     * Fail the service at startup on a malformed configuration. Runs after binding (Spring binds a
     * {@code @ConfigurationProperties} bean in {@code postProcessBeforeInitialization}), so every
     * value checked here is the value the service would actually have run with.
     *
     * @throws IllegalStateException naming the offending rule and what is wrong with it
     */
    @Override
    public void afterPropertiesSet() {
        if (maxWindowDays < 1) {
            throw new IllegalStateException("gmepay.aml.monitoring.max-window-days must be >= 1, got "
                    + maxWindowDays);
        }
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            String at = "gmepay.aml.monitoring.rules[" + i + "]";
            if (rule == null) {
                throw new IllegalStateException(at + " is null");
            }
            if (rule.getName() == null || rule.getName().isBlank()) {
                throw new IllegalStateException(at + ".name is required and must not be blank — an "
                        + "alert has to name the rule that raised it or nobody can act on it");
            }
            String name = rule.getName().trim();
            if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException(at + ".name '" + name + "' duplicates an earlier "
                        + "rule — rule names key the per-(partner, rule, window) de-duplication, so "
                        + "two rules sharing a name would suppress each other's alerts");
            }
            if (rule.getMetric() == null) {
                throw new IllegalStateException(at + ".metric is required — one of "
                        + java.util.Arrays.toString(AmlMetric.values()));
            }
            if (rule.getThreshold() == null) {
                throw new IllegalStateException(at + ".threshold is required — a rule with no "
                        + "threshold cannot fire, and a rule that cannot fire must not be "
                        + "configurable, because it would read as coverage that does not exist");
            }
            if (rule.getThreshold().signum() < 0) {
                throw new IllegalStateException(at + ".threshold must be >= 0, got "
                        + rule.getThreshold().toPlainString() + " — every metric is a non-negative "
                        + "quantity, so a negative threshold fires on every evaluation forever");
            }
            Integer windowDays = rule.getWindowDays();
            if (windowDays != null && windowDays < 1) {
                throw new IllegalStateException(at + ".window-days must be >= 1 when set, got "
                        + windowDays);
            }
            if (windowDays != null && windowDays > maxWindowDays) {
                throw new IllegalStateException(at + ".window-days " + windowDays + " exceeds "
                        + "gmepay.aml.monitoring.max-window-days (" + maxWindowDays + "), so the "
                        + "rule could never be evaluated over a readable window");
            }
        }
    }

    /**
     * One operator-configured monitoring rule.
     *
     * <p>A JavaBean rather than a record because relaxed binding of a {@code List<>} element needs a
     * no-arg constructor and setters; the validation that makes a half-filled instance impossible to
     * run with lives in {@link AmlMonitoringRules#afterPropertiesSet()}, not in the type.
     */
    public static class Rule {

        /** Operator-chosen identifier, carried on every alert and used for de-duplication. Required. */
        private String name;

        /** Which measurable quantity this rule watches. Required; see {@link AmlMetric}. */
        private AmlMetric metric;

        /**
         * The value the metric must exceed — <b>strictly</b> greater than — to fire. Required, and
         * must be non-negative. USD for the amount metrics, a plain count for the count metrics.
         */
        private BigDecimal threshold;

        /**
         * Trailing calendar days, ending at the evaluated window's last day, that this rule is
         * measured over. {@code null} means "the whole window the caller asked for". A rule whose
         * {@code windowDays} is longer than the window being evaluated is reported as NOT EVALUATED
         * rather than measured over less data than it asks for.
         */
        private Integer windowDays;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public AmlMetric getMetric() {
            return metric;
        }

        public void setMetric(AmlMetric metric) {
            this.metric = metric;
        }

        public BigDecimal getThreshold() {
            return threshold;
        }

        public void setThreshold(BigDecimal threshold) {
            this.threshold = threshold;
        }

        public Integer getWindowDays() {
            return windowDays;
        }

        public void setWindowDays(Integer windowDays) {
            this.windowDays = windowDays;
        }
    }
}
