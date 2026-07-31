package com.gme.pay.bff.client.db;

import com.gme.pay.bff.client.OperatorActionAuditClient;
import com.gme.pay.bff.persistence.OperatorActionAuditEntity;
import com.gme.pay.bff.persistence.OperatorActionAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * <b>The default {@link OperatorActionAuditClient}</b>: writes each audited operator action to this
 * service's own {@code operator_action_audit} table (Flyway V002).
 *
 * <h2>Why this exists — and why the default was NOT simply flipped to the REST client</h2>
 * The defect was the T1-1 class: {@code StubOperatorActionAuditClient} carried
 * {@code @ConditionalOnProperty(matchIfMissing = true)} and {@code GMEPAY_OPERATOR_ACTION_AUDIT_CLIENT}
 * was set in no values file, no compose service and no properties file — so an in-memory list with a
 * per-JVM {@code AtomicLong} was the live audit trail in every environment, colliding ids across
 * replicas and evaporating on restart.
 *
 * <p>T1-1's fix was to invert the default so the real client wins when the selector is absent, and
 * T1-1's first step was to <b>verify the real client's endpoint actually exists</b>. That check is
 * what stopped a straight inversion here: {@code RestOperatorActionAuditClient} POSTs to
 * {@code auth-identity POST /v1/audit/operator-actions}, and <b>no such endpoint exists anywhere in
 * this repo</b> — the only {@code /v1/audit} surfaces are config-registry's read endpoints. Flipping
 * the default onto it would have made {@link #recordDurable}'s fail-closed path throw on every audited
 * operator action, i.e. 500 the whole Operations console.
 *
 * <p>So the real implementation is the one that can exist today: persist the record in the service that
 * produces it. That is exactly what payment-executor did for the emitter half of T3-3 (its
 * {@code ops_alerts} table) while the consumer side was not real yet.
 *
 * <h2>What this is not</h2>
 * Not the regulator-grade audit log. config-registry owns the hash-chained {@code audit_log} with
 * {@code GET /v1/audit} and chain verification; this table is flat and append-only, so it proves what
 * an operator did, not that nobody edited the table afterwards. Moving these rows behind a
 * config-registry write endpoint (and then selecting {@code rest}) is the recorded follow-up.
 *
 * <h2>Two write modes, honestly implemented</h2>
 * <ul>
 *   <li>{@link #record} is best-effort: a failed write is logged at {@code ERROR} and the operator
 *       action proceeds.</li>
 *   <li>{@link #recordDurable} is fail-closed and can now genuinely fail — it commits in its <b>own
 *       transaction</b> ({@code REQUIRES_NEW}) so the row survives even if the caller's surrounding
 *       transaction later rolls back, and it throws {@link AuditWriteException} when the commit does
 *       not happen. Under the stub this method could not fail at all, which is what made
 *       "no money-affecting action without a durable audit record" a claim rather than a control.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "gmepay.operator-action-audit.client", havingValue = "db",
        matchIfMissing = true)
public class DbOperatorActionAuditClient implements OperatorActionAuditClient {

    private static final Logger log = LoggerFactory.getLogger(DbOperatorActionAuditClient.class);

    /** Prefix kept from the stub so the id shape the Admin UI already renders does not change. */
    private static final String ID_PREFIX = "OA-";

    private final OperatorActionAuditRepository repository;
    private final TransactionTemplate ownTransaction;
    private final Clock clock;

    // @Autowired is REQUIRED, not decorative: this class has two constructors, and Spring cannot pick
    // one without it — it falls back to a no-arg constructor that does not exist and the service
    // fails to boot. Same gotcha as the Rest*Client adapters' two-constructor shape.
    @Autowired
    public DbOperatorActionAuditClient(OperatorActionAuditRepository repository,
                                       PlatformTransactionManager transactionManager) {
        this(repository, transactionManager, Clock.systemUTC());
    }

    DbOperatorActionAuditClient(OperatorActionAuditRepository repository,
                                PlatformTransactionManager transactionManager,
                                Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        // A TransactionTemplate rather than @Transactional(REQUIRES_NEW): both write methods are
        // called from THIS class, and self-invocation never passes through the Spring proxy, so the
        // annotation would silently do nothing. This is the version that actually holds.
        this.ownTransaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public OperatorActionRecord record(String action, String target, String actor, String reason) {
        try {
            return persist(action, target, actor, reason);
        } catch (RuntimeException e) {
            log.error("FAILED to persist operator-action audit record action={} target={} actor={} — "
                            + "the action proceeds and this log line is the only record of it: {}",
                    action, target, actor, e.toString());
            return new OperatorActionRecord(null, action, target, actor, reason, Instant.now(clock));
        }
    }

    @Override
    public OperatorActionRecord recordDurable(String action, String target, String actor,
                                              String reason) {
        try {
            return persist(action, target, actor, reason);
        } catch (RuntimeException e) {
            log.error("durable operator-action audit write FAILED action={} target={} actor={} — the "
                            + "operator action is BLOCKED (no durable audit => no privileged "
                            + "action): {}", action, target, actor, e.toString());
            throw new AuditWriteException(
                    "operator-action audit write failed for action=" + action, e);
        }
    }

    /**
     * Commits in its <b>own</b> transaction ({@code REQUIRES_NEW}) so the audit row survives even if
     * the caller's surrounding transaction later rolls back. The fail-closed contract is "the record
     * exists BEFORE the action runs", which only means something if the record cannot be rolled back
     * together with the action.
     */
    private OperatorActionRecord persist(String action, String target, String actor, String reason) {
        Instant now = Instant.now(clock);
        OperatorActionAuditEntity saved = ownTransaction.execute(status -> repository.saveAndFlush(
                new OperatorActionAuditEntity(action, target, actor, reason, now)));
        if (saved == null || saved.getId() == null) {
            // Defensive: an id-less save means nothing was written, and for recordDurable that must
            // be a failure rather than a record with a null id.
            throw new IllegalStateException("operator_action_audit insert returned no id");
        }
        return new OperatorActionRecord(ID_PREFIX + saved.getId(), saved.getAction(),
                saved.getTarget(), saved.getActor(), saved.getReason(), saved.getRecordedAt());
    }
}
