package com.gme.pay.txn.idempotency;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes lapsed {@code idempotency_keys} rows.
 *
 * <p>Redis gave TTL away for free; a table does not, and "we will add a sweeper later" is how a
 * money-path table reaches a hundred million rows. One row is written per idempotent create and
 * lives 24 hours, so without this the table grows monotonically and the index behind it with it.
 *
 * <p><b>Not a correctness control.</b> {@link JdbcIdempotencyStore} filters on {@code expires_at}
 * at read time, so a key expires on schedule whether or not this ever runs. That separation is
 * deliberate: this job is ShedLock-guarded and therefore skippable by design (a lock held by a
 * dead replica delays it by up to {@code lockAtMostFor}), and a retention job must never be the
 * thing that decides when a key stops being replayable.
 *
 * <p>{@code @SchedulerLock} for the fleet-wide reason every other {@code @Scheduled} method in this
 * repository has one (T3-11 defect 3): N replicas would each run the same bulk delete. Harmless in
 * outcome but not in load, and the module's reflection guard requires every scheduled method to
 * carry a uniquely-named lock so that "is this one safe unlocked?" is not a judgement anyone
 * re-makes when a job body changes.
 */
@Component
@ConditionalOnBean(JdbcIdempotencyStore.class)
public class IdempotencyRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyRetentionSweeper.class);

    private final JdbcIdempotencyStore store;

    public IdempotencyRetentionSweeper(JdbcIdempotencyStore store) {
        this.store = store;
    }

    /**
     * Hourly by default. Not more often: the TTL is 24 hours, so an hour of lapsed rows is a
     * rounding error against a day of live ones, and a frequent bulk {@code DELETE} on a table the
     * money path writes to buys nothing.
     */
    @Scheduled(fixedDelayString = "${gmepay.idempotency.retention-sweep-ms:3600000}",
            initialDelayString = "${gmepay.idempotency.retention-initial-delay-ms:60000}")
    @SchedulerLock(name = "IdempotencyRetentionSweeper_sweep",
            lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    public void sweep() {
        try {
            int deleted = store.deleteExpired();
            if (deleted > 0) {
                log.info("idempotency retention: deleted {} lapsed keys", deleted);
            }
        } catch (RuntimeException e) {
            // A retention failure must never take down the scheduler thread or mask the next tick.
            log.warn("idempotency retention sweep failed: {}", e.toString());
        }
    }
}
