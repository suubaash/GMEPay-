package com.gme.pay.bff.alert;

import com.gme.pay.bff.persistence.OpsAlertEntity;
import com.gme.pay.bff.persistence.OpsAlertRepository;
import com.gme.pay.contracts.events.OpsAlertPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Durable, fleet-shared {@link OpsAlertStore} backed by the module's own {@code ops_alerts} table
 * (Flyway V001). <b>This is what lifts ops-partner-bff's replica ceiling</b> — the last one in the
 * platform.
 *
 * <p>The reasoning for a table rather than Redis is written out in full in the migration. The
 * decisive part is {@link #update}: the paging stamp and the operator ack are two independently
 * written parts of one alert, and here that read-modify-write happens under
 * {@code SELECT ... FOR UPDATE} inside a transaction, so neither can silently drop the other's
 * fields. On a Redis hash that needs {@code WATCH}/Lua, and getting it wrong shows up as an alert
 * that displays as never-paged.
 *
 * <h2>Failure posture: writes never throw, reads do</h2>
 * <ul>
 *   <li>{@link #add} is on the alert-consumption path, and the caller pages a human immediately
 *       afterwards. A database hiccup must therefore <b>not</b> propagate: it would abort the Kafka
 *       record before the page is dispatched, i.e. turn a storage failure into a <b>missed page</b>.
 *       So a failed insert is logged at {@code ERROR} with the whole alert (the last-resort record)
 *       and a transient view is returned; paging proceeds normally. Identical posture, and identical
 *       reasoning, to payment-executor's {@code OpsAlertArchive#record}.</li>
 *   <li>{@link #recent} / {@link #find} / {@link #update} <b>do</b> propagate. An operator surface
 *       that answers "no alerts" because its store is unreachable is worse than one that answers
 *       500: the first is a lie a human acts on. The escalation sweep sees the same exception, logs
 *       it and retries on the next tick — nothing is silenced permanently.</li>
 * </ul>
 */
public class JpaOpsAlertStore implements OpsAlertStore {

    private static final Logger log = LoggerFactory.getLogger(JpaOpsAlertStore.class);

    private final OpsAlertRepository repository;
    private final Clock clock;
    private final Duration retention;
    private final TransactionTemplate write;
    private final TransactionTemplate read;

    public JpaOpsAlertStore(OpsAlertRepository repository,
                            PlatformTransactionManager transactionManager,
                            Clock clock,
                            Duration retention) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = Objects.requireNonNull(retention, "retention");
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive: " + retention);
        }
        // Explicit TransactionTemplates rather than @Transactional, and this is load-bearing rather
        // than stylistic: the transaction boundary here is a CORRECTNESS requirement (update() does a
        // SELECT ... FOR UPDATE and must hold the row until it flushes), and @Transactional only
        // applies through a Spring proxy — so it would silently do nothing whenever this store is
        // constructed directly, and self-invocation would bypass it anyway. This version cannot be
        // wired in a way that loses the row lock.
        Objects.requireNonNull(transactionManager, "transactionManager");
        this.write = new TransactionTemplate(transactionManager);
        this.read = new TransactionTemplate(transactionManager);
        this.read.setReadOnly(true);
    }

    @Override
    public OpsAlertView add(OpsAlertPayload payload) {
        try {
            OpsAlertEntity saved = write.execute(status -> repository.save(new OpsAlertEntity(
                    payload.alertType(),
                    payload.severity(),
                    payload.subjectRef(),
                    payload.detail(),
                    payload.occurredAt(),
                    Instant.now(clock))));
            if (saved == null) {
                throw new IllegalStateException("ops_alerts insert returned nothing");
            }
            return saved.toView();
        } catch (RuntimeException e) {
            // Never rethrow — see the class javadoc. The alert still pages; only its row is lost,
            // and this log line is the record of it.
            log.error("FAILED to persist ops alert type={} severity={} subjectRef={} occurredAt={} "
                            + "detail={} — it will page but will NOT appear in "
                            + "GET /v1/admin/ops/alerts: {}",
                    payload.alertType(), payload.severity(), payload.subjectRef(),
                    payload.occurredAt(), payload.detail(), e.toString());
            // seq 0 is not a stored alert: update(0, ...) finds nothing and the dispatcher falls
            // back to stamping the returned view in memory, so paging still records its outcome.
            return OpsAlertView.from(0L, payload);
        }
    }

    @Override
    public List<OpsAlertView> recent(String severity, String alertType, int limit) {
        return read.execute(status -> repository.findRecent(
                        blankToNull(severity), blankToNull(alertType),
                        PageRequest.of(0, OpsAlertStore.clampLimit(limit)))
                .stream()
                .map(OpsAlertEntity::toView)
                .toList());
    }

    @Override
    public Optional<OpsAlertView> find(long seq) {
        return read.execute(status -> repository.findById(seq).map(OpsAlertEntity::toView));
    }

    /**
     * {@inheritDoc}
     *
     * <p>Runs the mutator against the row while it is held exclusively. The mutator is a pure
     * function on the view (it only ever produces {@code withPaging} / {@code withAck} copies), so
     * the changed parts are copied back onto the entity and flushed by the transaction commit.
     */
    @Override
    public Optional<OpsAlertView> update(long seq, UnaryOperator<OpsAlertView> mutator) {
        return write.execute(status -> repository.findByIdForUpdate(seq).map(entity -> {
            OpsAlertView before = entity.toView();
            OpsAlertView after = mutator.apply(before);
            if (after == null) {
                return before;
            }
            entity.applyPaging(after.paging());
            entity.applyAck(after.ack());
            repository.saveAndFlush(entity);
            return entity.toView();
        }));
    }

    @Override
    public int size() {
        Long count = read.execute(status -> repository.count());
        return count == null || count > Integer.MAX_VALUE ? Integer.MAX_VALUE : count.intValue();
    }

    /**
     * Drop alerts written before the retention cutoff. Returns rows deleted.
     *
     * <p>Retention, not correctness — nothing reads on the age of a row. Without it the table grows
     * by one row per consumed alert forever, which is how the "durable" answer to a bounded deque
     * becomes an unbounded table.
     */
    public int prune() {
        Instant cutoff = Instant.now(clock).minus(retention);
        Integer result = write.execute(status -> repository.deleteWrittenBefore(cutoff));
        int deleted = result == null ? 0 : result;
        if (deleted > 0) {
            log.info("pruned {} ops_alerts rows written before {} ({}-day retention)",
                    deleted, cutoff, retention.toDays());
        }
        return deleted;
    }

    /** The configured retention window, for the sweeper's startup log. */
    public Duration retention() {
        return retention;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
