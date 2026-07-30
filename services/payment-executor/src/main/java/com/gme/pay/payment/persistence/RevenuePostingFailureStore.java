package com.gme.pay.payment.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * Durable, replayable record of revenue-ledger postings that never reached revenue-ledger
 * (gap T2-1 / CFO#6 — "ledger posting is fire-and-forget with no replay job").
 *
 * <p>Revenue-ledger calls on the commit path must never fail a payment that already moved money, so the
 * client swallows every transport/HTTP error. That policy is right, but until now the swallowed posting
 * was gone: the revenue for that transaction simply never existed and only a log line remembered it.
 * Every swallowed failure is now written here with the exact request payload, keyed
 * {@code (reference, postingType)}, so an ops job can re-POST it. Revenue-ledger's endpoints are
 * idempotent on their reference/txnRef, so replaying is safe.
 *
 * <p><b>Recording never throws.</b> This sits inside a path that has already committed real money; a
 * database hiccup while writing the failure record must not turn a successful payment into an error. A
 * failure to persist the failure is logged at ERROR (the last-resort signal) and swallowed.
 *
 * <h2>Draining (gap T2-5)</h2>
 * <p>The PENDING rows are now drained by {@code RevenuePostingReplayService}: a bounded, backed-off,
 * idempotent sweep that re-POSTs the stored payload and either lands it (REPLAYED) or, once the attempt
 * budget is exhausted, stops and ALERTS (POISON). This class stays the WRITE side only — it knows nothing
 * about the replay beyond scheduling the first attempt, so a change to the retry policy cannot alter what
 * gets recorded on the money path.
 */
@Service
public class RevenuePostingFailureStore {

    /** Per-transaction revenue capture — {@code POST /v1/revenue/capture}. */
    public static final String TYPE_REVENUE_CAPTURE = "REVENUE_CAPTURE";
    /** Settlement rounding residual — {@code POST /v1/journals/rounding-residual}. */
    public static final String TYPE_ROUNDING_RESIDUAL = "ROUNDING_RESIDUAL";
    /** Two-sided commission split — {@code POST /v1/revenue/commission-split}. */
    public static final String TYPE_COMMISSION_SPLIT = "COMMISSION_SPLIT";
    /** Cancel/refund reversal journal — {@code POST /v1/journals/reversal}. */
    public static final String TYPE_REVERSAL_JOURNAL = "REVERSAL_JOURNAL";

    private static final Logger log = LoggerFactory.getLogger(RevenuePostingFailureStore.class);
    private static final int MAX_ERROR_LEN = 1024;

    /**
     * How far out a HOT-PATH re-failure pushes the next replay attempt.
     *
     * <p>Not zero: the hot path failed a moment ago, so revenue-ledger is almost certainly still down and an
     * immediate replay would just add load to a struggling service and burn an attempt from the bound.
     */
    static final java.time.Duration HOT_PATH_REFAILURE_BACKOFF = java.time.Duration.ofMinutes(5);

    private final RevenuePostingFailureRepository repository;
    private final ObjectMapper objectMapper;

    public RevenuePostingFailureStore(RevenuePostingFailureRepository repository,
                                      @Nullable ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * Persist (or re-stamp) one failed posting so it can be replayed.
     *
     * @param reference   the reference the posting was keyed on (txnRef / cancelled reference)
     * @param postingType one of the {@code TYPE_*} constants
     * @param payload     the request body that failed; serialised to JSON for the replay
     * @param error       the failure description (truncated to the column width)
     */
    public void record(String reference, String postingType,
                       @Nullable Map<String, Object> payload, String error) {
        try {
            Instant now = Instant.now();
            String json = toJson(payload);
            String trimmedError = truncate(error);
            repository.findByReferenceAndPostingType(reference, postingType)
                    .ifPresentOrElse(
                            existing -> {
                                existing.recordAnotherFailure(json, trimmedError, now,
                                        HOT_PATH_REFAILURE_BACKOFF);
                                repository.save(existing);
                            },
                            () -> repository.save(new RevenuePostingFailureEntity(
                                    reference, postingType, json, trimmedError, now)));
            log.warn("revenue posting {} for ref={} persisted for replay ({})",
                    postingType, reference, trimmedError);
        } catch (RuntimeException ex) {
            // Last resort: money already moved, so never propagate. This log line is the only
            // remaining trace, which is exactly the situation this table exists to avoid — hence ERROR.
            log.error("FAILED to persist revenue posting failure {} for ref={} — posting is now"
                    + " unrecoverable except from logs: {}", postingType, reference, ex.toString());
        }
    }

    private String toJson(@Nullable Map<String, Object> payload) {
        if (payload == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            // A non-serialisable payload must not cost us the row; keep the toString() form.
            return payload.toString();
        }
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= MAX_ERROR_LEN ? error : error.substring(0, MAX_ERROR_LEN);
    }
}
