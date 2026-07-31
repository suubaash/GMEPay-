package com.gme.pay.prefunding.aml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gme.pay.prefunding.audit.PrefundingAuditor;
import com.gme.pay.prefunding.outbox.OutboxWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies the operator-configured {@link AmlMonitoringRules} to a partner's window evidence and, for
 * each rule that trips, raises an alert through prefunding's EXISTING outbox + audit pipeline — gap
 * T5-3.
 *
 * <h2>With no rules configured this component does nothing at all</h2>
 *
 * <p>{@link AmlMonitoringRules} ships with an EMPTY rule list because a monitoring threshold is a
 * compliance policy input that no engineer may invent. The consequence is deliberate and is stated
 * here as bluntly as it is stated there: <b>until compliance populates
 * {@code gmepay.aml.monitoring.rules}, prefunding raises no AML monitoring alerts.</b> On an empty
 * list {@link #evaluate} returns before touching a repository, a transaction, the outbox or the audit
 * log. It is a no-op, not a quiet pass.
 *
 * <h2>This raises an alert; it does not make a determination</h2>
 *
 * <p>An alert from here says "a configured threshold was exceeded by this measured quantity over this
 * window". It is EVIDENCE routed to a human. It is not a suspicious-activity determination, not a
 * filing, and not by itself an AML control — the control is the compliance process that consumes it,
 * which does not exist inside this service.
 *
 * <h2>Existing pipeline, no new table</h2>
 *
 * <p>A firing writes exactly two rows, in ONE transaction, exactly as {@code TierAlertEvaluator}
 * does for a low-balance tier: an {@code outbox} row of type
 * {@value #EVENT_TYPE_AML_MONITORING_ALERT} (KafkaEventPublisher prefixes {@code gmepay.}, so it
 * lands on topic {@code gmepay.prefunding.aml-monitoring.alert}, ADR-001) and one hash-chained
 * {@code audit_log} row on {@link PrefundingAuditor#AGG_AML_USAGE}. No Flyway migration and no new
 * table: the ledger the alert is computed from is already append-only and already audited, and a
 * bespoke alert table would be a second, weaker copy of it.
 *
 * <h2>De-duplication is IN-MEMORY and therefore NOT durable — read this before relying on it</h2>
 *
 * <p>Re-reading the same window must not re-alert, so a (partner, rule, window) key is remembered
 * after a successful raise and suppresses subsequent firings of the same key. That memory is a
 * bounded in-process map. It is <b>lost on restart and not shared between replicas</b>: after a
 * redeploy, or on a second instance, the same window CAN alert again. This is stated rather than
 * papered over because the alternative claims are both worse — a durable table would need a
 * migration this slice is not taking, and pretending the in-memory map is durable would leave
 * somebody assuming exactly-once delivery of a compliance signal.
 *
 * <p>The direction of that failure is the tolerable one: a duplicate alert is noise a reviewer
 * dismisses, whereas a missed alert is the failure that matters. {@code TierAlertEvaluator} can do
 * better only because its hysteresis reads a durable {@code balance_alert} row; there is no
 * equivalent row here, and inventing one is a separate decision.
 */
@Component
public class AmlMonitoringEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AmlMonitoringEvaluator.class);

    /**
     * Event type discriminator: KafkaEventPublisher maps it to topic
     * {@code gmepay.prefunding.aml-monitoring.alert}. Deliberately NOT the existing
     * {@code prefunding.alert} topic — a low-float operational alert and an AML monitoring signal
     * have different audiences, different retention expectations and different sensitivity, and
     * merging them would put compliance traffic on an ops channel.
     */
    public static final String EVENT_TYPE_AML_MONITORING_ALERT = "prefunding.aml-monitoring.alert";

    /** Upper bound on remembered (partner, rule, window) keys. Oldest-accessed keys fall out first. */
    static final int DEDUP_CAPACITY = 10_000;

    private final AmlMonitoringRules rules;
    private final OutboxWriter outbox;
    private final PrefundingAuditor audit;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    /**
     * Access-ordered bounded LRU of keys already alerted on. Synchronised because evaluation can be
     * driven concurrently by independent reads. Two threads racing the same key can both observe it
     * as absent and both raise — an accepted duplicate, consistent with the paragraph above.
     */
    private final Map<String, Boolean> alreadyRaised = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > DEDUP_CAPACITY;
                }
            });

    public AmlMonitoringEvaluator(AmlMonitoringRules rules,
                                  OutboxWriter outbox,
                                  PrefundingAuditor audit,
                                  ObjectMapper objectMapper,
                                  PlatformTransactionManager transactionManager) {
        this.rules = rules;
        this.outbox = outbox;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Run every configured rule against {@code evidence}.
     *
     * <p>A rule fires when its metric is <b>strictly greater than</b> its threshold; equality passes.
     * A rule that declares {@code windowDays} is measured over the trailing sub-window of that many
     * days ending at the evidence window's last day — and if the evidence window is SHORTER than the
     * rule asks for, the rule is reported as NOT EVALUATED rather than measured against less data
     * than it needs (a 30-day rule scored on 7 days of activity would report a pass it did not earn).
     *
     * <p>Writes are explicitly transactional rather than annotation-driven so that the empty-rule and
     * nothing-fired paths open no transaction at all. When at least one alert has to be raised, all
     * of them commit together with their audit rows, on the caller's transaction if one is already
     * open (the transactional-Outbox contract, ADR-001).
     */
    public AmlEvaluation evaluate(AmlWindowEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence required");
        List<AmlMonitoringRules.Rule> configured = rules.getRules();
        if (configured.isEmpty()) {
            // No rules => nothing checked and nothing raised. No transaction, no outbox row, no
            // audit row, no repository call. See the class javadoc: this is the shipped default.
            return AmlEvaluation.noRules(evidence.partnerId(), evidence.fromDailyKey(),
                    evidence.toDailyKey());
        }

        List<AmlEvaluation.FiredRule> fired = new ArrayList<>();
        List<AmlEvaluation.UnevaluatedRule> notEvaluated = new ArrayList<>();
        List<PendingAlert> pending = new ArrayList<>();

        for (AmlMonitoringRules.Rule rule : configured) {
            String name = rule.getName().trim();
            AmlWindowEvidence measured;
            if (rule.getWindowDays() == null) {
                measured = evidence;
            } else {
                try {
                    measured = evidence.trailingWindow(rule.getWindowDays());
                } catch (IllegalArgumentException e) {
                    notEvaluated.add(new AmlEvaluation.UnevaluatedRule(name, e.getMessage()));
                    log.warn("aml monitoring: rule '{}' not evaluated for partner {}: {}",
                            name, evidence.partnerId(), e.getMessage());
                    continue;
                }
            }
            BigDecimal observed = measured.observe(rule.getMetric());
            if (observed.compareTo(rule.getThreshold()) <= 0) {
                continue;
            }
            String key = dedupKey(evidence.partnerId(), name, measured.fromDailyKey(),
                    measured.toDailyKey());
            boolean firstTime = !alreadyRaised.containsKey(key);
            fired.add(new AmlEvaluation.FiredRule(name, rule.getMetric(), observed,
                    rule.getThreshold(), measured.fromDailyKey(), measured.toDailyKey(),
                    measured.spanDays(), firstTime));
            if (firstTime) {
                pending.add(new PendingAlert(key, name, rule.getMetric(), observed,
                        rule.getThreshold(), measured.fromDailyKey(), measured.toDailyKey(),
                        measured.spanDays()));
            }
        }

        if (!pending.isEmpty()) {
            transactions.executeWithoutResult(status -> {
                for (PendingAlert alert : pending) {
                    raise(evidence.partnerId(), alert);
                }
            });
            // Only remembered once the writes have committed: marking before would let a rolled-back
            // transaction permanently suppress the alert it failed to record.
            for (PendingAlert alert : pending) {
                alreadyRaised.put(alert.key(), Boolean.TRUE);
            }
        }

        return new AmlEvaluation(evidence.partnerId(), evidence.fromDailyKey(),
                evidence.toDailyKey(), configured.size(), List.copyOf(fired),
                List.copyOf(notEvaluated));
    }

    /** Enqueue the outbox event and write the audit row for one firing. Same transaction. */
    private void raise(String partnerCode, PendingAlert alert) {
        Instant raisedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("eventType", EVENT_TYPE_AML_MONITORING_ALERT);
        payload.put("partnerCode", partnerCode);
        payload.put("rule", alert.rule());
        payload.put("metric", alert.metric().name());
        // Both observed and threshold ride as decimal strings per docs/MONEY_CONVENTION.md — the
        // amount metrics are USD, and the count metrics use the same encoding so a consumer parses
        // one shape rather than branching on the metric.
        payload.put("observed", alert.observed().toPlainString());
        payload.put("threshold", alert.threshold().toPlainString());
        payload.put("comparison", "observed > threshold");
        payload.put("windowFrom", alert.windowFrom());
        payload.put("windowTo", alert.windowTo());
        payload.put("windowDays", alert.windowDays());
        payload.put("raisedAt", raisedAt.toString());
        // Says what the alert is NOT, on the wire, so a downstream consumer cannot mistake a
        // threshold breach for a completed compliance determination.
        payload.put("determination", "NONE — threshold exceeded; requires compliance review");
        outbox.enqueue(partnerCode, EVENT_TYPE_AML_MONITORING_ALERT, payload.toString());

        audit.amlMonitoringRuleFired(partnerCode, alert.rule(), alert.metric().name(),
                alert.observed(), alert.threshold(), alert.windowFrom(), alert.windowTo(),
                alert.windowDays());

        log.info("aml monitoring alert raised: partner={} rule={} metric={} observed={} "
                        + "threshold={} window={}..{}",
                partnerCode, alert.rule(), alert.metric(), alert.observed().toPlainString(),
                alert.threshold().toPlainString(), alert.windowFrom(), alert.windowTo());
    }

    /**
     * The de-duplication key. It includes the WINDOW, not just the partner and rule, so a genuinely
     * new period alerts again while a repeated read of the same period does not.
     */
    private static String dedupKey(String partnerId, String rule, String from, String to) {
        return partnerId + '|' + rule + '|' + from + '|' + to;
    }

    /** A firing that has passed de-duplication and is queued for the write transaction. */
    private record PendingAlert(String key, String rule, AmlMetric metric, BigDecimal observed,
                                BigDecimal threshold, String windowFrom, String windowTo,
                                int windowDays) { }
}
