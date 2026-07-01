package com.gme.pay.prefunding.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gme.pay.prefunding.client.ConfigRegistryClient;
import com.gme.pay.prefunding.outbox.OutboxWriter;
import com.gme.pay.prefunding.persistence.BalanceAlertEntity;
import com.gme.pay.prefunding.persistence.BalanceAlertRepository;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Low-balance tier alerting (Slice 5 — Prefunding). Called after EVERY balance mutation,
 * inside the same transaction as the mutation itself, so the {@code balance_alert} row and
 * the outbox event commit (or roll back) atomically with the new balance — the same
 * transactional-Outbox contract the rest of the platform uses (ADR-001).
 *
 * <h2>Tier semantics</h2>
 *
 * <p>With {@code pct = balance / lowBalanceThreshold * 100}, an alert tier fires when the
 * balance crosses DOWN through its boundary: {@code TIER_95} at 95%, {@code TIER_85} at 85%,
 * {@code TIER_70} at 70%. "Crossing" compares the pre-mutation balance against the
 * post-mutation balance — a balance that was already below a boundary does not re-fire it
 * (e.g. 72% → 68% raises TIER_70 only, never TIER_85/95 again).
 *
 * <h2>Hysteresis / exactly-once</h2>
 *
 * <p>Before raising, the LATEST alert for the (partner, tier) pair is consulted: while it is
 * unacknowledged, the tier stays suppressed even if the balance oscillates back above and
 * below the boundary (68% → 71% → 69% does NOT re-raise). Once an operator acknowledges the
 * alert, the next downward crossing raises a fresh one.
 *
 * <h2>BREACH → auto-suspend proposal</h2>
 *
 * <p>A balance going negative raises a {@code BREACH} alert and proposes (4-eyes, never
 * applies) partner suspension via config-registry's change_request queue with
 * {@code proposedBy='system'} (ADR-008 carve-out). The comparisons are exact
 * ({@link BigDecimal#multiply} + {@code compareTo}; no division, no rounding).
 */
@Component
public class TierAlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TierAlertEvaluator.class);

    /** Event type discriminator: KafkaEventPublisher maps it to topic {@code gmepay.prefunding.alert}. */
    public static final String EVENT_TYPE_ALERT = "prefunding.alert";

    /**
     * Ops control-tower alert event type (#5). Same outbox drain; KafkaEventPublisher maps it to the
     * shared ops topic {@code gmepay.ops.alert} where the ops monitors converge. We CONVERGE onto this
     * — every {@code prefunding.alert} that fires ALSO emits a {@code FLOAT_LOW} ops alert, so the
     * existing per-partner tier stream is untouched while ops gets a unified low-float signal.
     */
    public static final String EVENT_TYPE_OPS_ALERT = com.gme.pay.contracts.events.OpsAlertPayload.EVENT_TYPE;

    /** {@code alertType} raised on the ops topic for a low/breached partner float. */
    public static final String OPS_ALERT_FLOAT_LOW = "FLOAT_LOW";

    public static final String TIER_95 = "TIER_95";
    public static final String TIER_85 = "TIER_85";
    public static final String TIER_70 = "TIER_70";
    public static final String BREACH = "BREACH";

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** Boundaries evaluated mild → severe; each fires independently on its own crossing. */
    private record TierBoundary(String tier, BigDecimal boundaryPct) { }

    private static final List<TierBoundary> TIERS = List.of(
            new TierBoundary(TIER_95, new BigDecimal("95")),
            new TierBoundary(TIER_85, new BigDecimal("85")),
            new TierBoundary(TIER_70, new BigDecimal("70")));

    private final BalanceAlertRepository alerts;
    private final OutboxWriter outbox;
    private final ConfigRegistryClient configRegistry;
    private final ObjectMapper objectMapper;

    public TierAlertEvaluator(BalanceAlertRepository alerts,
                              OutboxWriter outbox,
                              ConfigRegistryClient configRegistry,
                              ObjectMapper objectMapper) {
        this.alerts = alerts;
        this.outbox = outbox;
        this.configRegistry = configRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * Evaluate tier crossings for {@code row} after a balance mutation. Call from within the
     * mutating transaction, AFTER the new balance has been set on the (managed) entity.
     *
     * @param row             the partner_balance row carrying the POST-mutation balance
     * @param previousBalance the balance BEFORE the mutation (crossing detection)
     */
    public void afterBalanceChange(PartnerBalanceEntity row, BigDecimal previousBalance) {
        String partnerCode = row.getPartnerId();
        BigDecimal newBalance = row.getBalance();
        BigDecimal threshold = row.getLowBalanceThreshold();

        if (threshold != null && threshold.signum() > 0) {
            for (TierBoundary tier : TIERS) {
                // boundary value in money terms: threshold * pct / 100. Compare
                // balance*100 against threshold*pct so the arithmetic stays exact.
                BigDecimal cut = threshold.multiply(tier.boundaryPct());
                boolean wasAtOrAbove = previousBalance.multiply(HUNDRED).compareTo(cut) >= 0;
                boolean nowBelow = newBalance.multiply(HUNDRED).compareTo(cut) < 0;
                if (wasAtOrAbove && nowBelow && tierIsArmed(partnerCode, tier.tier())) {
                    raise(partnerCode, tier.tier(), newBalance, threshold);
                }
            }
        }

        boolean breachedNow = newBalance.signum() < 0 && previousBalance.signum() >= 0;
        if (breachedNow && tierIsArmed(partnerCode, BREACH)) {
            BigDecimal thresholdOrZero = threshold != null ? threshold : BigDecimal.ZERO;
            raise(partnerCode, BREACH, newBalance, thresholdOrZero);
            configRegistry.proposePartnerSuspension(partnerCode,
                    "prefunding balance breached: " + newBalance.toPlainString() + " "
                            + row.getCurrency());
        }
    }

    /**
     * Hysteresis check: a tier is armed when it has never fired, or when its latest alert
     * has been acknowledged by an operator. An unacknowledged latest alert keeps the tier
     * suppressed across oscillations.
     */
    private boolean tierIsArmed(String partnerCode, String tier) {
        return alerts.findTopByPartnerCodeAndTierOrderByIdDesc(partnerCode, tier)
                .map(BalanceAlertEntity::isAcknowledged)
                .orElse(true);
    }

    /** Insert the balance_alert row and enqueue the outbox event — same transaction. */
    private void raise(String partnerCode, String tier, BigDecimal balance, BigDecimal threshold) {
        Instant raisedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        BalanceAlertEntity saved = alerts.save(
                new BalanceAlertEntity(partnerCode, tier, balance, threshold, raisedAt));

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("alertId", saved.getId());
        payload.put("partnerCode", partnerCode);
        payload.put("tier", tier);
        // Money rides as decimal strings per docs/MONEY_CONVENTION.md.
        payload.put("balanceUsd", balance.toPlainString());
        payload.put("thresholdUsd", threshold.toPlainString());
        payload.put("raisedAt", raisedAt.toString());
        outbox.enqueue(partnerCode, EVENT_TYPE_ALERT, payload.toString());

        // #5 — converge onto the ops control tower: same crossing ALSO emits a FLOAT_LOW ops alert.
        emitFloatLowOpsAlert(partnerCode, tier, balance, threshold, raisedAt);

        log.info("balance alert raised: partner={} tier={} balance={} threshold={}",
                partnerCode, tier, balance.toPlainString(), threshold.toPlainString());
    }

    /**
     * Enqueue an {@code OpsAlertPayload}(alertType=FLOAT_LOW) outbox row alongside the tier alert, so a
     * low/breached partner float lands on {@code gmepay.ops.alert}. Severity scales with how far below
     * the threshold the balance sits: TIER_95→INFO, TIER_85/TIER_70→WARN, BREACH→CRITICAL.
     * {@code subjectRef} is the partner code; {@code detail} carries balance + threshold (decimal strings).
     */
    private void emitFloatLowOpsAlert(String partnerCode, String tier, BigDecimal balance,
                                      BigDecimal threshold, Instant raisedAt) {
        String severity = severityForTier(tier);
        String detail = "partner=" + partnerCode + " tier=" + tier
                + " balanceUsd=" + balance.toPlainString()
                + " thresholdUsd=" + threshold.toPlainString();
        ObjectNode ops = objectMapper.createObjectNode();
        ops.put("eventType", EVENT_TYPE_OPS_ALERT);
        ops.put("alertType", OPS_ALERT_FLOAT_LOW);
        ops.put("severity", severity);
        ops.put("subjectRef", partnerCode);
        ops.put("detail", detail);
        ops.put("occurredAt", raisedAt.toString());
        outbox.enqueue(partnerCode, EVENT_TYPE_OPS_ALERT, ops.toString());
    }

    /** INFO (95%) → WARN (85%/70%) → CRITICAL (breach), by how far below threshold the float has fallen. */
    private static String severityForTier(String tier) {
        return switch (tier) {
            case TIER_95 -> "INFO";
            case BREACH -> "CRITICAL";
            default -> "WARN"; // TIER_85, TIER_70
        };
    }
}
