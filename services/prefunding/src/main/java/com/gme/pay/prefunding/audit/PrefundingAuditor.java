package com.gme.pay.prefunding.audit;

import com.gme.pay.audit.AuditActors;
import com.gme.pay.audit.DbAuditPublisher;
import com.gme.pay.prefunding.persistence.PartnerBalanceEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes the {@code audit_log} row for every prefunding balance, hold and limit mutation —
 * the closure of gap T5-1 / CISO §9 "Prefunding balance movement — NO".
 *
 * <h2>What was missing</h2>
 *
 * <p>{@code ledger_entry} (V002) recorded {@code (partner_id, txn_ref, entry_type, amount, currency,
 * created_at)}. That is a money ledger and a good one, but as an audit trail it has three holes the
 * CISO audit named exactly: <b>no actor</b>, <b>no reason</b>, <b>no IP</b>. And two whole classes of
 * mutation never reached it at all — {@code setCreditLimit} / {@code pushPartnerLimits} change the
 * partner's credit headroom and AML caps by writing {@code partner_balance} with no ledger row
 * whatsoever, and {@code provision} creates the opening balance the same way. So "who gave this
 * partner USD 2,000,000 of credit headroom, and when, and why" had no answer anywhere in the
 * database.
 *
 * <h2>Chains</h2>
 *
 * <p>The hash chain is per {@code (aggregate_type, aggregate_id)}. All three aggregate types key on
 * the <b>partner code</b>, so one partner's history is one chain and verifying it never touches
 * another partner's rows. They are split by subject rather than lumped together because the read
 * patterns differ by orders of magnitude in volume:
 *
 * <ul>
 *   <li>{@link #AGG_BALANCE} — money and holds actually moving. High volume: one row per payment.</li>
 *   <li>{@link #AGG_LIMIT} — credit headroom and AML caps. Low volume, high consequence; kept in its
 *       own chain so "who raised this partner's limit" is a short list rather than a needle in a
 *       month of debits.</li>
 *   <li>{@link #AGG_AML_USAGE} — the cumulative daily/monthly/annual counters. High volume, and
 *       distinct from balance because a cumulative charge consumes <i>cap</i>, not float.</li>
 * </ul>
 *
 * <h2>Transaction participation</h2>
 *
 * <p>{@link DbAuditPublisher#append} runs on the caller's transaction, so every audit row here
 * commits if and only if the balance mutation it describes commits (ADR-007). A rolled-back deduct
 * leaves no audit row — correct, because nothing happened. The inverse (an audit row for a movement
 * that rolled back) would be worse than none.
 *
 * <h2>Actors</h2>
 *
 * <p>Every method takes {@code actorId}. Passing {@code null} means "derive it from the request
 * currently being served" via {@link AuditActorResolver#currentActor()} — which is a lookup, not a
 * guess, and yields {@link AuditActors#UNATTRIBUTED} when there is no request. Background paths pass
 * an explicit {@code AuditActors.system("<component>")}: see {@link #SYSTEM_REVERSAL_CONSUMER} and
 * {@link #SYSTEM_BREACH_AUTO_SUSPEND}. The bare {@code "system"} literal is not writable at all —
 * {@code AuditEvent.newEvent} throws on it.
 */
@Component
public class PrefundingAuditor {

    private static final Logger log = LoggerFactory.getLogger(PrefundingAuditor.class);

    // ---- aggregate types (lower_snake_case, <= 64 chars) ----

    /** Float actually moving or being held for a partner. {@code aggregate_id} = partner code. */
    public static final String AGG_BALANCE = "partner_balance";

    /** Credit headroom + AML caps for a partner. {@code aggregate_id} = partner code. */
    public static final String AGG_LIMIT = "partner_limit";

    /** AML cumulative usage counters for a partner. {@code aggregate_id} = partner code. */
    public static final String AGG_AML_USAGE = "partner_aml_usage";

    // ---- event types (UPPER_SNAKE verbs, <= 64 chars) ----

    /** Opening balance row created for a newly onboarded partner. */
    public static final String BALANCE_PROVISIONED = "BALANCE_PROVISIONED";
    /** Float consumed by a confirmed payment (DEBIT). */
    public static final String BALANCE_DEBITED = "BALANCE_DEBITED";
    /** Float added by an operator top-up (CREDIT with no txnRef). */
    public static final String BALANCE_CREDITED = "BALANCE_CREDITED";
    /** A prior DEBIT reversed on operator instruction (CREDIT tagged with the txnRef). */
    public static final String BALANCE_DEBIT_REVERSED = "BALANCE_DEBIT_REVERSED";
    /** Held float returned because the payment reached REVERSED (the {@code payment.reversed} event). */
    public static final String REVERSED_FLOAT_RELEASED = "REVERSED_FLOAT_RELEASED";
    /** A hold placed against available funds (authorize phase / CPM token issuance). */
    public static final String FUNDS_RESERVED = "FUNDS_RESERVED";
    /** A hold converted into a real debit (confirm phase). */
    public static final String FUNDS_CAPTURED = "FUNDS_CAPTURED";
    /** A hold freed without debiting (expiry / decline). */
    public static final String FUNDS_RELEASED = "FUNDS_RELEASED";
    /** The balance went negative and an auto-suspend was proposed for 4-eyes approval. */
    public static final String BREACH_SUSPENSION_PROPOSED = "BREACH_SUSPENSION_PROPOSED";
    /** Credit headroom set on its own ({@code PUT /v1/prefunding/{id}/credit-limit}). */
    public static final String CREDIT_LIMIT_SET = "CREDIT_LIMIT_SET";
    /** Credit headroom + AML caps pushed together from config-registry (IR-pf-2). */
    public static final String PARTNER_LIMITS_PUSHED = "PARTNER_LIMITS_PUSHED";
    /** Cumulative AML usage consumed by an authorize. */
    public static final String CUMULATIVE_USAGE_CHARGED = "CUMULATIVE_USAGE_CHARGED";
    /** Cumulative AML usage returned to the period (void / decline / expiry). */
    public static final String CUMULATIVE_USAGE_REVERSED = "CUMULATIVE_USAGE_REVERSED";
    /**
     * An operator-configured AML monitoring rule tripped on a partner's window evidence (gap T5-3).
     * Recorded on {@link #AGG_AML_USAGE} beside the charges and reverses it was computed from, so the
     * alert and the ledger movements that produced it read as one chain rather than two systems.
     */
    public static final String AML_MONITORING_RULE_FIRED = "AML_MONITORING_RULE_FIRED";

    // ---- named system principals ----

    /**
     * The {@code payment.reversed} Kafka consumer path. Off-request by construction (there is no HTTP
     * caller and no human), so it names itself rather than being recorded as unattributed.
     */
    public static final String SYSTEM_REVERSAL_CONSUMER =
            AuditActors.system("payment-reversed-consumer");

    /** The breach hook that proposes a partner suspension when the float goes negative. */
    public static final String SYSTEM_BREACH_AUTO_SUSPEND =
            AuditActors.system("prefunding-breach-auto-suspend");

    /**
     * The AML monitoring evaluator (gap T5-3). A rule firing is a platform decision taken against a
     * partner by a configured threshold, not an action by whoever happened to read the evidence
     * endpoint that triggered the evaluation — attributing it to that reader would put a human's name
     * on a machine's determination. It is also reachable off-request entirely, so it names its
     * component.
     */
    public static final String SYSTEM_AML_MONITORING = AuditActors.system("prefunding-aml-monitoring");

    /**
     * The local-dev demo seed runner ({@code PrefundingSeedRunner}). It creates a partner with USD
     * 50,000 of float on an empty table, which is a balance appearing from nowhere — the fact that it
     * only happens outside production is a reason to LABEL it, not a reason to leave it unrecorded. A
     * seeded balance that shows up in a real environment should be findable by actor.
     */
    public static final String SYSTEM_DEMO_SEED_RUNNER = AuditActors.system("demo-seed-runner");

    private final DbAuditPublisher publisher;
    private final AuditActorResolver actors;

    public PrefundingAuditor(DbAuditPublisher publisher, AuditActorResolver actors) {
        this.publisher = publisher;
        this.actors = actors;
    }

    // -------------------------------------------------------------------------
    // State snapshots
    // -------------------------------------------------------------------------

    /**
     * The float position of a partner at one instant — what {@code before}/{@code after} compare.
     *
     * <p>{@code reserved} and {@code creditLimit} are included alongside {@code balance} because
     * "available" is {@code balance + creditLimit - reserved}: a reader who saw only the balance
     * could not tell whether a movement was legitimate against the partner's actual headroom, which
     * is the question an investigator asks about a hold.
     */
    public record BalanceState(BigDecimal balance, BigDecimal reserved, BigDecimal creditLimit,
                               String currency) {

        /** Snapshot the current in-memory state of a {@code partner_balance} row. */
        public static BalanceState of(PartnerBalanceEntity row) {
            return new BalanceState(row.getBalance(), row.getReserved(), row.getCreditLimit(),
                    row.getCurrency());
        }

        /** A state with the balance replaced — for capturing the pre-mutation position. */
        public BalanceState withBalance(BigDecimal other) {
            return new BalanceState(other, reserved, creditLimit, currency);
        }

        /** A state with balance AND reserved replaced — for hold movements. */
        public BalanceState with(BigDecimal otherBalance, BigDecimal otherReserved) {
            return new BalanceState(otherBalance, otherReserved, creditLimit, currency);
        }
    }

    /** The mutation itself: what moved, keyed to what, and why. */
    public record Movement(String entryType, BigDecimal amountUsd, String txnRef,
                           Long ledgerEntryId, String reason) {

        /** A movement with no free-text reason (the API offers none on this path). */
        public static Movement of(String entryType, BigDecimal amountUsd, String txnRef,
                                  Long ledgerEntryId) {
            return new Movement(entryType, amountUsd, txnRef, ledgerEntryId, null);
        }
    }

    /** Credit headroom + AML caps as configured. {@code null} cap = no cap for that period. */
    public record Limits(BigDecimal creditLimitUsd, BigDecimal amlDailyCapUsd,
                         BigDecimal amlMonthlyCapUsd, BigDecimal amlAnnualCapUsd,
                         Integer amlDailyTxnCountCap) {

        public static Limits of(PartnerBalanceEntity row) {
            return new Limits(row.getCreditLimit(), row.getAmlDailyCapUsd(),
                    row.getAmlMonthlyCapUsd(), row.getAmlAnnualCapUsd(),
                    row.getAmlDailyTxnCountCap());
        }

        /** The all-null position of a partner that had no balance row yet (limits pushed pre-provisioning). */
        public static Limits none() {
            return new Limits(null, null, null, null, null);
        }
    }

    /** Cumulative AML usage for the txn's KST day / month / year. */
    public record UsageState(BigDecimal dailyUsd, BigDecimal monthlyUsd, BigDecimal annualUsd) {
    }

    // -------------------------------------------------------------------------
    // Emitters
    // -------------------------------------------------------------------------

    /**
     * Audit one float movement or hold change on {@link #AGG_BALANCE}.
     *
     * <p>{@code before_jsonb} is the pre-mutation position; {@code after_jsonb} is the post-mutation
     * position <i>plus</i> the movement descriptor. Key order is fixed by the literal call sequence
     * below — see {@link CanonicalJson} for why that matters to the hash.
     *
     * @param actorId who acted, or {@code null} to take the actor of the request being served
     */
    public void balanceMovement(String partnerCode, String eventType, BalanceState before,
                                BalanceState after, Movement movement, String actorId) {
        byte[] beforeJson = state(before).bytes();
        byte[] afterJson = state(after)
                .str("entryType", movement.entryType())
                .money("amountUsd", movement.amountUsd())
                .str("txnRef", movement.txnRef())
                .num("ledgerEntryId", movement.ledgerEntryId())
                .str("reason", movement.reason())
                .bytes();
        append(AGG_BALANCE, partnerCode, eventType, beforeJson, afterJson, actorId);
    }

    /**
     * Audit a credit-headroom / AML-cap change on {@link #AGG_LIMIT}. This is the mutation class that
     * had NO record of any kind before: it writes {@code partner_balance} and no ledger row.
     *
     * @param actorId who acted, or {@code null} to take the actor of the request being served
     */
    public void limitChange(String partnerCode, String eventType, Limits before, Limits after,
                            String reason, String actorId) {
        byte[] beforeJson = limits(before).bytes();
        byte[] afterJson = limits(after).str("reason", reason).bytes();
        append(AGG_LIMIT, partnerCode, eventType, beforeJson, afterJson, actorId);
    }

    /**
     * Audit a cumulative AML usage change on {@link #AGG_AML_USAGE}.
     *
     * @param actorId who acted, or {@code null} to take the actor of the request being served
     */
    public void cumulativeUsage(String partnerCode, String eventType, UsageState before,
                                UsageState after, String txnRef, BigDecimal amountUsd,
                                String actorId) {
        byte[] beforeJson = usage(before).bytes();
        byte[] afterJson = usage(after)
                .str("txnRef", txnRef)
                .money("amountUsd", amountUsd)
                .bytes();
        append(AGG_AML_USAGE, partnerCode, eventType, beforeJson, afterJson, actorId);
    }

    /**
     * Audit an AML monitoring rule firing on {@link #AGG_AML_USAGE} (gap T5-3).
     *
     * <p>The row records the rule that fired, what it measured, the value observed, the threshold it
     * exceeded and the exact window — everything needed to reproduce the determination from the
     * append-only ledger without the alert message beside it. The {@code before} position is the same
     * rule with {@code fired:false}, so a reader sees the rule's definition and its outcome in one row
     * rather than having to fetch the configuration that was live at the time.
     *
     * <p>Attributed to {@link #SYSTEM_AML_MONITORING}: a threshold comparison is a platform
     * determination with no human in the loop, and the operator who happened to request the evidence
     * read did not make it.
     *
     * <p>This row is evidence that a CONFIGURED rule tripped. It is not, on its own, a suspicious
     * activity determination — that judgement belongs to compliance, downstream of this alert.
     *
     * @param observed  the measured value (USD for the amount metrics, a plain count otherwise)
     * @param threshold the configured value it was strictly greater than
     */
    public void amlMonitoringRuleFired(String partnerCode, String rule, String metric,
                                       BigDecimal observed, BigDecimal threshold,
                                       String windowFrom, String windowTo, Integer windowDays) {
        byte[] beforeJson = CanonicalJson.object()
                .str("rule", rule)
                .str("metric", metric)
                .money("threshold", threshold)
                .bool("fired", false)
                .bytes();
        byte[] afterJson = CanonicalJson.object()
                .str("rule", rule)
                .str("metric", metric)
                .money("threshold", threshold)
                .bool("fired", true)
                .money("observed", observed)
                .str("windowFrom", windowFrom)
                .str("windowTo", windowTo)
                .num("windowDays", windowDays)
                .bytes();
        append(AGG_AML_USAGE, partnerCode, AML_MONITORING_RULE_FIRED, beforeJson, afterJson,
                SYSTEM_AML_MONITORING);
    }

    /**
     * Audit the auto-suspend proposal raised when a partner's float goes negative. Recorded on
     * {@link #AGG_BALANCE} (it is a consequence of a balance movement, and belongs beside the
     * movement that caused it) and attributed to {@link #SYSTEM_BREACH_AUTO_SUSPEND} — a genuine
     * platform action with no human in the loop, named so it is distinguishable from a lost identity.
     */
    public void breachSuspensionProposed(String partnerCode, BigDecimal balance, String currency,
                                         String reason) {
        byte[] beforeJson = CanonicalJson.object()
                .str("suspensionProposed", "false")
                .bytes();
        byte[] afterJson = CanonicalJson.object()
                .str("suspensionProposed", "true")
                .money("balanceUsd", balance)
                .str("currency", currency)
                .str("reason", reason)
                .bytes();
        append(AGG_BALANCE, partnerCode, BREACH_SUSPENSION_PROPOSED, beforeJson, afterJson,
                SYSTEM_BREACH_AUTO_SUSPEND);
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /** Fixed-order float position. */
    private static CanonicalJson state(BalanceState s) {
        return CanonicalJson.object()
                .money("balance", s == null ? null : s.balance())
                .money("reserved", s == null ? null : s.reserved())
                .money("creditLimit", s == null ? null : s.creditLimit())
                .str("currency", s == null ? null : s.currency());
    }

    /** Fixed-order limits position. */
    private static CanonicalJson limits(Limits l) {
        Limits v = l == null ? Limits.none() : l;
        return CanonicalJson.object()
                .money("creditLimitUsd", v.creditLimitUsd())
                .money("amlDailyCapUsd", v.amlDailyCapUsd())
                .money("amlMonthlyCapUsd", v.amlMonthlyCapUsd())
                .money("amlAnnualCapUsd", v.amlAnnualCapUsd())
                .num("amlDailyTxnCountCap", v.amlDailyTxnCountCap());
    }

    /** Fixed-order cumulative usage position. */
    private static CanonicalJson usage(UsageState u) {
        return CanonicalJson.object()
                .money("dailyUsd", u == null ? null : u.dailyUsd())
                .money("monthlyUsd", u == null ? null : u.monthlyUsd())
                .money("annualUsd", u == null ? null : u.annualUsd());
    }

    /**
     * Seal and INSERT. {@code recordedAt} is truncated to MICROS because it is inside the digest and
     * must equal what the {@code TIMESTAMP} column stores.
     */
    private void append(String aggregateType, String aggregateId, String eventType,
                        byte[] beforeJson, byte[] afterJson, String actorId) {
        String actor = actorId != null ? actorId : actors.currentActor();
        // The IP is always the request's, even for an explicitly-named system principal: the breach
        // auto-suspend fires *inside* the request whose deduct pushed the float negative, and knowing
        // which caller triggered it is the useful half of that row. Null off-request.
        String ip = actors.currentActorIp();
        try {
            publisher.append(aggregateType, aggregateId, actor, ip, eventType,
                    beforeJson, afterJson, Instant.now().truncatedTo(ChronoUnit.MICROS));
        } catch (IllegalArgumentException e) {
            // An unusable actorId is a programming error in the caller, not backpressure — and
            // DbAuditPublisher deliberately does not swallow it. Re-throwing here would fail the
            // money movement, so instead the row is written UNATTRIBUTED (never dropped: a movement
            // with no audit row is the failure mode this gap exists to remove) and the bug is
            // logged loudly enough to be found.
            log.error("audit: refusing actorId '{}' for {}/{} {} — writing the row as {} instead. "
                            + "This is a caller bug: use AuditActors.attested/system/service/unverified.",
                    actor, aggregateType, aggregateId, eventType, AuditActors.UNATTRIBUTED, e);
            publisher.append(aggregateType, aggregateId, AuditActors.UNATTRIBUTED, ip, eventType,
                    beforeJson, afterJson, Instant.now().truncatedTo(ChronoUnit.MICROS));
        }
    }
}
