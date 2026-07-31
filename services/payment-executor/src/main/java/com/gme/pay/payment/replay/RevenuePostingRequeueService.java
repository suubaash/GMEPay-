package com.gme.pay.payment.replay;

import com.gme.pay.payment.opsrun.LedgerOpsJob;
import com.gme.pay.payment.opsrun.LedgerOpsRunExecutor;
import com.gme.pay.payment.opsrun.LedgerOpsRunRecorder;
import com.gme.pay.payment.persistence.RevenuePostingFailureEntity;
import com.gme.pay.payment.persistence.RevenuePostingFailureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Moves POISON revenue postings back to PENDING after the cause has been fixed
 * (gap <b>T2-5</b> follow-up, opened by <b>T3-12</b>).
 *
 * <h2>The hole this fills</h2>
 * <p>{@code POISON} is terminal by design: "a POISON row is never retried again — that is the point". That
 * design is right when the reason is a property of the row. It is <b>wrong</b> when the reason is a property
 * of the <em>server</em>, and T3-12 produced exactly that case: revenue-ledger's two journal endpoints
 * returned 406 on every call because of a content-negotiation defect, {@link RestRevenuePostingReplayClient}
 * classified 406 as a permanent rejection, and {@code RevenuePostingReplayService} poisoned the row on the
 * <b>first</b> sweep. With no requeue path anywhere in the service, a server bug that was fixed in an
 * afternoon had permanently orphaned every rounding-residual and reversal journal, recoverable only by
 * hand-editing {@code revenue_posting_failures} in production.
 *
 * <p>Two changes close it, and both are needed. The classification of 406 was corrected (see
 * {@code RestRevenuePostingReplayClient#isRetryableStatus}) so this case does not <em>arise</em> again; this
 * service is the escape hatch for the general case, because no classification is going to be right about
 * every future server defect. Retryability buys hours — the attempt budget is eight attempts of exponential
 * backoff — whereas a defect found a week later needs this.
 *
 * <h2>Properties</h2>
 * <ol>
 *   <li><b>Idempotent.</b> Only POISON rows are selected. Requeueing the same set twice moves N rows the
 *       first time and 0 the second, because the first call already made them PENDING. No exception, no
 *       double-reset: a nervous operator running the command again is a no-op, not a corruption.</li>
 *   <li><b>Never unfiltered.</b> A call with neither a posting-type filter nor an id set is rejected. "Requeue
 *       everything" is a different, much larger decision than "requeue the postings broken by the 406", and
 *       an empty request body must not silently mean the larger one.</li>
 *   <li><b>Structurally-unreplayable rows are skipped, not requeued.</b> A row poisoned because no payload was
 *       ever captured cannot be re-POSTed at any point in the future; requeueing it would spend the whole
 *       attempt budget arriving back at POISON. They are counted and reported separately so the operator sees
 *       that they were considered and why they were left alone.</li>
 *   <li><b>Audited.</b> Every requeue writes a {@code ledger_ops_runs} row through the same
 *       {@link LedgerOpsRunExecutor} the sweep uses — job {@code REVENUE_POSTING_REQUEUE}, trigger
 *       {@code OPERATOR}, the operator id, and a summary. Resetting {@code attempts} discards audit history
 *       from the row itself, so the run ledger is where "who un-poisoned these, when, and why" lives.</li>
 *   <li><b>Bounded.</b> At most {@code gmepay.revenue-posting-replay.requeue-max-rows} rows per call, so one
 *       request cannot re-arm an unbounded backlog against a revenue-ledger that may still be fragile.</li>
 * </ol>
 *
 * <p><b>ABANDONED is not requeued by posting type.</b> Abandoning is an operator's judgement that a posting
 * should not be booked; a bulk type-filtered requeue overturning that silently would make the status
 * meaningless. An operator who genuinely wants an abandoned row back names its id — an explicit, per-row act.
 */
@Service
public class RevenuePostingRequeueService {

    private static final Logger log = LoggerFactory.getLogger(RevenuePostingRequeueService.class);

    /** Rejected: the caller named no posting type and no id. */
    public static final String ERROR_NO_SELECTOR = "REQUEUE_NO_SELECTOR";

    /** Rejected: the caller named a posting type that does not exist. */
    public static final String ERROR_UNKNOWN_POSTING_TYPE = "REQUEUE_UNKNOWN_POSTING_TYPE";

    /** The posting types a requeue may target — the same four the replay client knows how to send. */
    static final Set<String> KNOWN_POSTING_TYPES = Set.of(
            com.gme.pay.payment.persistence.RevenuePostingFailureStore.TYPE_REVENUE_CAPTURE,
            com.gme.pay.payment.persistence.RevenuePostingFailureStore.TYPE_ROUNDING_RESIDUAL,
            com.gme.pay.payment.persistence.RevenuePostingFailureStore.TYPE_COMMISSION_SPLIT,
            com.gme.pay.payment.persistence.RevenuePostingFailureStore.TYPE_REVERSAL_JOURNAL);

    private final RevenuePostingFailureRepository repository;
    private final LedgerOpsRunExecutor executor;
    private final Clock clock;
    private final int maxRows;

    public RevenuePostingRequeueService(
            RevenuePostingFailureRepository repository,
            LedgerOpsRunExecutor executor,
            Clock clock,
            @Value("${gmepay.revenue-posting-replay.requeue-max-rows:1000}") int maxRows) {
        this.repository = repository;
        this.executor = executor;
        this.clock = clock;
        this.maxRows = maxRows > 0 ? maxRows : 1000;
    }

    /**
     * What one requeue did. Every count is a disjoint classification of the rows the selectors matched.
     *
     * @param matched            rows the selectors found, whatever their status
     * @param requeued           POISON rows moved to PENDING with {@code attempts=0}
     * @param skippedNotPoison   matched rows that were not POISON (already PENDING, REPLAYED, or ABANDONED)
     * @param skippedUnreplayable POISON rows with no captured payload — requeueing cannot help them
     * @param requeuedReferences the {@code reference/postingType} of each row moved, for the operator's log
     */
    public record RequeueResult(int matched, int requeued, int skippedNotPoison, int skippedUnreplayable,
                                List<String> requeuedReferences) {

        public String describe() {
            return "matched=" + matched + " requeued=" + requeued
                    + " skippedNotPoison=" + skippedNotPoison
                    + " skippedUnreplayable=" + skippedUnreplayable;
        }
    }

    /** A selector the caller got wrong. Carries a stable error code the controller maps to 400. */
    public static class RequeueRejectedException extends RuntimeException {
        private final String errorCode;

        public RequeueRejectedException(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }

    /**
     * Requeue the POISON rows matching the selectors, recorded in {@code ledger_ops_runs}.
     *
     * @param postingTypes posting types to target (case-insensitive); may be null/empty if {@code ids} is not
     * @param ids          specific {@code revenue_posting_failures.id} values; may be null/empty if
     *                     {@code postingTypes} is not
     * @param reason       why, stamped onto each row and onto the run summary
     * @param operatorId   who asked ({@code X-Operator-Id})
     * @throws RequeueRejectedException when the selectors are absent or name an unknown posting type —
     *                                  thrown BEFORE the run wrapper, so a caller's typo is a 400 rather than
     *                                  a FAILED run row and a CRITICAL alert
     */
    public LedgerOpsRunExecutor.RunResult<RequeueResult> requeue(@Nullable List<String> postingTypes,
                                                                 @Nullable List<Long> ids,
                                                                 @Nullable String reason,
                                                                 @Nullable String operatorId) {
        List<String> types = normaliseTypes(postingTypes);
        List<Long> targetIds = ids == null ? List.of() : ids.stream().filter(java.util.Objects::nonNull).toList();

        if (types.isEmpty() && targetIds.isEmpty()) {
            throw new RequeueRejectedException(ERROR_NO_SELECTOR,
                    "a requeue must name postingTypes and/or ids; an unfiltered requeue of every POISON row "
                            + "is a different decision and is not available on this endpoint");
        }

        LedgerOpsRunRecorder.RunKey key =
                LedgerOpsRunRecorder.RunKey.operator(LedgerOpsJob.REVENUE_POSTING_REQUEUE, null, operatorId);
        return executor.execute(key,
                () -> requeueOnce(types, targetIds, reason, Instant.now(clock)),
                r -> LedgerOpsRunExecutor.RunSummary.of(
                        r.describe() + " reason=" + (reason == null || reason.isBlank() ? "(none)" : reason),
                        r.requeued()));
    }

    /** Visible for tests so the transition can be driven from a fixed clock. */
    RequeueResult requeueOnce(List<String> postingTypes, List<Long> ids, @Nullable String reason, Instant now) {
        // A LinkedHashSet keyed on the entity id, so a call naming BOTH a posting type and an id that the type
        // filter already selected requeues that row once, not twice. Overlapping selectors are the normal case
        // for an operator narrowing down from a type sweep to a handful of stragglers.
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        List<RevenuePostingFailureEntity> candidates = new ArrayList<>();

        if (!postingTypes.isEmpty()) {
            for (RevenuePostingFailureEntity row : repository.findForRequeueByPostingType(
                    RevenuePostingFailureEntity.STATUS_POISON, postingTypes, PageRequest.of(0, maxRows))) {
                if (seen.add(row.getId())) {
                    candidates.add(row);
                }
            }
        }
        if (!ids.isEmpty()) {
            for (RevenuePostingFailureEntity row : repository.findForRequeueByIds(ids)) {
                if (seen.add(row.getId())) {
                    candidates.add(row);
                }
            }
        }

        int skippedNotPoison = 0;
        int skippedUnreplayable = 0;
        List<String> requeued = new ArrayList<>();

        for (RevenuePostingFailureEntity row : candidates) {
            if (!RevenuePostingFailureEntity.STATUS_POISON.equals(row.getStatus())) {
                // This is where idempotency actually lives: on a second identical call every row this call
                // already moved is PENDING, so it lands here and nothing is written.
                skippedNotPoison++;
                continue;
            }
            if (!row.isReplayable()) {
                skippedUnreplayable++;
                log.warn("revenue posting requeue SKIPPED {} ref={} — no payload was ever captured, so no "
                                + "number of attempts can land it; it stays POISON",
                        row.getPostingType(), row.getReference());
                continue;
            }
            row.requeue(reason, now);
            repository.save(row);
            requeued.add(row.getReference() + "/" + row.getPostingType());
        }

        RequeueResult result = new RequeueResult(candidates.size(), requeued.size(), skippedNotPoison,
                skippedUnreplayable, List.copyOf(requeued));
        log.info("revenue posting requeue: {} (types={} ids={} reason={})",
                result.describe(), postingTypes, ids, reason);
        return result;
    }

    /** Upper-cases, de-duplicates and validates the requested posting types. */
    private static List<String> normaliseTypes(@Nullable List<String> postingTypes) {
        if (postingTypes == null) {
            return List.of();
        }
        LinkedHashSet<String> types = new LinkedHashSet<>();
        for (String raw : postingTypes) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String type = raw.trim().toUpperCase(Locale.ROOT);
            if (!KNOWN_POSTING_TYPES.contains(type)) {
                // Refused rather than ignored: silently dropping a mistyped type would report "requeued=0"
                // and let the operator believe the backlog was already clear.
                throw new RequeueRejectedException(ERROR_UNKNOWN_POSTING_TYPE,
                        "unknown postingType '" + raw + "'; known types are " + KNOWN_POSTING_TYPES);
            }
            types.add(type);
        }
        return List.copyOf(types);
    }
}
