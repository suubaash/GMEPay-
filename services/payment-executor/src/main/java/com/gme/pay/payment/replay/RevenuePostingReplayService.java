package com.gme.pay.payment.replay;

import com.gme.pay.payment.opsrun.LedgerOpsJob;
import com.gme.pay.payment.opsrun.LedgerOpsRunExecutor;
import com.gme.pay.payment.opsrun.LedgerOpsRunRecorder;
import com.gme.pay.payment.opsrun.LedgerOpsRunTrigger;
import com.gme.pay.payment.persistence.RevenuePostingFailureEntity;
import com.gme.pay.payment.persistence.RevenuePostingFailureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Drains {@code revenue_posting_failures} back into revenue-ledger — the replay job gap <b>T2-5</b> asked for
 * and gap T2-1 explicitly deferred ("nothing drains that table yet").
 *
 * <h2>Why this is a money fix, not housekeeping</h2>
 * <p>Every revenue-ledger call on the commit path is deliberately non-blocking: a ledger outage must not fail a
 * payment whose money already moved. T2-1 made the swallowed posting durable instead of a log line, but nothing
 * ever re-sent it — so revenue GME had genuinely earned could stay permanently absent from the ledger, and the
 * only symptom was a report that quietly under-stated the period.
 *
 * <h2>Five properties, each deliberate</h2>
 * <ol>
 *   <li><b>Idempotent.</b> Every revenue-ledger endpoint is keyed on its reference/txnRef, so a replay of a
 *       posting that has since landed is a no-op there and reports {@code 200} rather than {@code 201}. This
 *       service reads that distinction and records {@code ALREADY_PRESENT}: the goal is "the posting is in the
 *       ledger", not "this job put it there". A row whose posting later succeeded by another route is therefore
 *       CLEARED, not double-booked.</li>
 *   <li><b>Exactly one attempt per row per sweep.</b> A row is selected only when
 *       {@code next_attempt_at <= now}, and the first thing that happens after the call is a persisted
 *       re-schedule. There is no inner retry loop — a burst of retries against a struggling revenue-ledger is
 *       how a partial outage becomes a full one.</li>
 *   <li><b>Bounded by the existing {@code attempts} counter.</b> No second counter was introduced. That does
 *       mean hot-path re-failures share the budget with replays, which is correct: {@code attempts} answers
 *       "how many times has this posting failed to land", and eight failures by any route is the point at
 *       which a human is needed.</li>
 *   <li><b>Backed off, persistently.</b> Exponential from {@code base-backoff-seconds}, capped at
 *       {@code max-backoff-seconds}, stored in {@code next_attempt_at} — so a restart cannot reset every row's
 *       schedule and re-storm a downstream that is still down.</li>
 *   <li><b>Terminal state ALERTS.</b> Exhausting the budget (or hitting a body revenue-ledger will always
 *       reject) sets {@code POISON} and raises a CRITICAL {@code ops.alert} through the existing T3-3 pipeline.
 *       Poisoning without alerting would recreate the silent loss in a new column.</li>
 * </ol>
 *
 * <p><b>No transaction spans the sweep.</b> Each row's state change commits on its own (Spring Data's
 * {@code save}), so a failure part-way through a batch keeps the rows already resolved instead of rolling back
 * work that genuinely reached revenue-ledger — and the run itself is still recorded by
 * {@link LedgerOpsRunExecutor}.
 */
@Service
public class RevenuePostingReplayService {

    /** {@code alertType} raised when a posting is given up on. */
    public static final String ALERT_TYPE_POISON = "REVENUE_POSTING_POISON";

    private static final Logger log = LoggerFactory.getLogger(RevenuePostingReplayService.class);

    private final RevenuePostingFailureRepository repository;
    private final RevenuePostingReplayPort port;
    private final LedgerOpsRunExecutor executor;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Duration maxBackoff;

    public RevenuePostingReplayService(
            RevenuePostingFailureRepository repository,
            RevenuePostingReplayPort port,
            LedgerOpsRunExecutor executor,
            Clock clock,
            @Value("${gmepay.revenue-posting-replay.batch-size:100}") int batchSize,
            @Value("${gmepay.revenue-posting-replay.max-attempts:8}") int maxAttempts,
            @Value("${gmepay.revenue-posting-replay.base-backoff-seconds:60}") long baseBackoffSeconds,
            @Value("${gmepay.revenue-posting-replay.max-backoff-seconds:21600}") long maxBackoffSeconds) {
        this.repository = repository;
        this.port = port;
        this.executor = executor;
        this.clock = clock;
        this.batchSize = batchSize > 0 ? batchSize : 100;
        this.maxAttempts = maxAttempts > 0 ? maxAttempts : 8;
        this.baseBackoff = Duration.ofSeconds(baseBackoffSeconds > 0 ? baseBackoffSeconds : 60);
        this.maxBackoff = Duration.ofSeconds(maxBackoffSeconds > 0 ? maxBackoffSeconds : 21600);
    }

    /** What one sweep did. Every count is a disjoint classification of the rows examined. */
    public record ReplaySweepResult(int examined, int posted, int alreadyPresent, int stillFailing,
                                    int poisoned, List<String> poisonedReferences) {

        /** Postings that are now on the ledger, however they got there. */
        public int landed() {
            return posted + alreadyPresent;
        }

        public String describe() {
            return "examined=" + examined + " posted=" + posted + " alreadyPresent=" + alreadyPresent
                    + " stillFailing=" + stillFailing + " poison=" + poisoned;
        }
    }

    /**
     * Run one sweep wrapped in the durable run ledger + failure alerting (gap T2-5 sub-gap 4).
     *
     * @param trigger    scheduler or operator
     * @param operatorId who asked, for an operator-triggered run
     */
    public LedgerOpsRunExecutor.RunResult<ReplaySweepResult> run(LedgerOpsRunTrigger trigger,
                                                                @Nullable String operatorId) {
        LedgerOpsRunRecorder.RunKey key = trigger == LedgerOpsRunTrigger.OPERATOR
                ? LedgerOpsRunRecorder.RunKey.operator(LedgerOpsJob.REVENUE_POSTING_REPLAY, null, operatorId)
                : LedgerOpsRunRecorder.RunKey.scheduled(LedgerOpsJob.REVENUE_POSTING_REPLAY, null);
        return executor.execute(key,
                () -> sweepOnce(Instant.now(clock)),
                r -> LedgerOpsRunExecutor.RunSummary.of(r.describe(), r.examined()));
    }

    /**
     * One sweep pass over the rows that are due. Visible for tests so the scheduling behaviour can be driven
     * from a fixed clock rather than by waiting.
     *
     * @param now the instant to evaluate due-ness and stamp attempts with
     */
    public ReplaySweepResult sweepOnce(Instant now) {
        List<RevenuePostingFailureEntity> due =
                repository.findDueForReplay(now, PageRequest.of(0, batchSize));
        if (due.isEmpty()) {
            return new ReplaySweepResult(0, 0, 0, 0, 0, List.of());
        }

        int posted = 0;
        int alreadyPresent = 0;
        int stillFailing = 0;
        List<String> poisonedRefs = new ArrayList<>();

        for (RevenuePostingFailureEntity row : due) {
            boolean called = row.isReplayable();
            RevenuePostingReplayOutcome outcome = called
                    ? port.replay(row.getPostingType(), row.getPayload())
                    // Nothing to send. Not a transport problem and no amount of waiting fixes it, so it is
                    // poisoned on sight instead of consuming eight attempts to reach the same conclusion.
                    : RevenuePostingReplayOutcome.unreplayable(
                            "no request payload was captured for this posting");

            if (outcome.landed()) {
                row.recordReplaySuccess(outcome.detail(), now);
                repository.save(row);
                if (outcome.kind() == RevenuePostingReplayOutcome.Kind.POSTED) {
                    posted++;
                } else {
                    alreadyPresent++;
                }
                log.info("revenue posting {} ref={} REPLAYED after {} failed attempt(s): {}",
                        row.getPostingType(), row.getReference(), row.getAttempts(), outcome.detail());
                continue;
            }

            // attempts is incremented by the entity, so compare against the value AFTER this attempt.
            boolean budgetExhausted = row.getAttempts() + 1 >= maxAttempts;
            if (outcome.permanent() || budgetExhausted) {
                String reason = outcome.permanent()
                        ? "unreplayable: " + outcome.detail()
                        : "attempt budget exhausted (" + maxAttempts + " attempts): " + outcome.detail();
                row.poison(reason, now, called);
                repository.save(row);
                poisonedRefs.add(row.getReference() + "/" + row.getPostingType());
                alertPoison(row, reason);
                continue;
            }

            Duration backoff = backoffFor(row.getAttempts() + 1);
            row.recordReplayFailure(outcome.detail(), now, backoff);
            repository.save(row);
            stillFailing++;
            log.warn("revenue posting {} ref={} replay attempt {} failed ({}); next attempt in {}",
                    row.getPostingType(), row.getReference(), row.getAttempts(), outcome.detail(), backoff);
        }

        ReplaySweepResult result = new ReplaySweepResult(due.size(), posted, alreadyPresent, stillFailing,
                poisonedRefs.size(), List.copyOf(poisonedRefs));
        log.info("revenue posting replay sweep: {}", result.describe());
        return result;
    }

    /**
     * Exponential backoff on the attempt count, capped. Deliberately not jittered: this is a single-replica
     * sweeper over a per-row schedule, so there is no thundering herd to spread — and a deterministic schedule
     * is one an operator can predict from the row.
     */
    Duration backoffFor(int attempts) {
        int exponent = Math.max(0, Math.min(attempts - 1, 20));
        Duration scaled = baseBackoff.multipliedBy(1L << exponent);
        return scaled.compareTo(maxBackoff) > 0 ? maxBackoff : scaled;
    }

    /**
     * A poisoned posting means booked revenue is missing from the ledger and the machine has stopped trying.
     * That is the loudest thing this service can say, so it goes out CRITICAL on the T3-3 pipeline (persisted
     * to {@code ops_alerts}, published, and pushed to the notification sink).
     */
    private void alertPoison(RevenuePostingFailureEntity row, String reason) {
        log.error("revenue posting {} ref={} POISONED after {} attempt(s) — booked revenue is MISSING from "
                        + "revenue-ledger and will not be retried: {}",
                row.getPostingType(), row.getReference(), row.getAttempts(), reason);
        executor.alert(ALERT_TYPE_POISON, "CRITICAL", row.getReference(),
                "revenue posting " + row.getPostingType() + " for ref=" + row.getReference()
                        + " could not be replayed into revenue-ledger after " + row.getAttempts()
                        + " attempt(s) and has been POISONED (no further retries). The revenue is NOT on the "
                        + "ledger. Reason: " + reason);
    }
}
