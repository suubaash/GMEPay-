package com.gme.pay.prefunding.aml;

import java.math.BigDecimal;
import java.util.List;

/**
 * The outcome of running the operator-configured monitoring rules against one partner's window
 * evidence — gap T5-3.
 *
 * <p>It reports three things separately, and the separation is the point: rules that FIRED, rules
 * that could NOT be evaluated, and how many rules were configured at all. A caller must be able to
 * distinguish "nothing tripped" from "nothing was checked", and the second case is the normal one —
 * {@link AmlMonitoringRules} ships empty, so {@code rulesConfigured == 0} and every list is empty
 * until compliance supplies thresholds.
 *
 * @param partnerId       the partner the rules were run against
 * @param fromDailyKey    the evidence window's inclusive lower bound
 * @param toDailyKey      the evidence window's inclusive upper bound
 * @param rulesConfigured how many rules existed to run — zero means NOTHING was checked
 * @param fired           rules whose metric exceeded their threshold, in configuration order
 * @param notEvaluated    rules that could not be run against this window, each with the reason
 */
public record AmlEvaluation(String partnerId,
                            String fromDailyKey,
                            String toDailyKey,
                            int rulesConfigured,
                            List<FiredRule> fired,
                            List<UnevaluatedRule> notEvaluated) {

    /** The result of an evaluation that had no rules to run: nothing checked, nothing raised. */
    public static AmlEvaluation noRules(String partnerId, String fromDailyKey, String toDailyKey) {
        return new AmlEvaluation(partnerId, fromDailyKey, toDailyKey, 0, List.of(), List.of());
    }

    /**
     * One rule that tripped.
     *
     * @param rule        the operator-chosen rule name
     * @param metric      what was measured
     * @param observed    the value measured over {@code windowFrom..windowTo}
     * @param threshold   the configured value it strictly exceeded
     * @param windowFrom  inclusive lower bound of the sub-window the rule was measured over
     * @param windowTo    inclusive upper bound (always the evidence window's last day)
     * @param windowDays  inclusive span of that sub-window
     * @param alertRaised {@code true} when this evaluation actually enqueued the alert;
     *                    {@code false} when an identical (partner, rule, window) alert had already
     *                    been raised by this instance and was suppressed. The rule still tripped —
     *                    suppression is reported, not hidden, so a caller never reads a de-duplicated
     *                    re-evaluation as a clean result
     */
    public record FiredRule(String rule, AmlMetric metric, BigDecimal observed, BigDecimal threshold,
                            String windowFrom, String windowTo, int windowDays,
                            boolean alertRaised) { }

    /**
     * One rule that could NOT be run against this window — today the only cause is a rule whose
     * {@code windowDays} is longer than the window that was read.
     *
     * <p>These are reported rather than dropped. A rule silently skipped is coverage that does not
     * exist while looking like coverage that does, which is the same failure mode as a threshold
     * nobody set.
     */
    public record UnevaluatedRule(String rule, String reason) { }
}
