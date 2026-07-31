package com.gme.pay.payment.replay;

import com.gme.pay.payment.persistence.RevenuePostingFailureEntity;
import com.gme.pay.payment.persistence.RevenuePostingFailureRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only aggregation over {@code revenue_posting_failures} for the ops surface and for the day-close
 * report's {@code REVENUE_POSTINGS_OUTSTANDING} variance (gap <b>T2-5</b>).
 *
 * <p>Kept separate from {@code RevenuePostingFailureStore} (the write side, which must never throw because it
 * sits on the money path) and from {@link RevenuePostingReplayService} (the drain). A read that can throw is
 * fine — it is answering an operator, not committing a payment.
 *
 * <p>Aggregation happens in SQL rather than by loading rows, so the query cost does not grow with the size of
 * the table; it is intended to be safe to call on every day-close.
 */
@Service
public class RevenuePostingOutstandingQuery {

    private final RevenuePostingFailureRepository repository;

    public RevenuePostingOutstandingQuery(RevenuePostingFailureRepository repository) {
        this.repository = repository;
    }

    /** The outstanding picture, aggregated. */
    @Transactional(readOnly = true)
    public RevenuePostingOutstandingView outstanding() {
        long pending = 0;
        long poison = 0;
        long abandoned = 0;
        long replayed = 0;
        Instant oldestOutstanding = null;
        List<RevenuePostingOutstandingView.Bucket> buckets = new ArrayList<>();

        for (Object[] r : repository.outstandingBreakdown()) {
            String status = (String) r[0];
            String postingType = (String) r[1];
            long count = ((Number) r[2]).longValue();
            Instant oldestAt = toInstant(r[3]);
            buckets.add(new RevenuePostingOutstandingView.Bucket(status, postingType, count, oldestAt));

            switch (status) {
                case RevenuePostingFailureEntity.STATUS_PENDING -> pending += count;
                case RevenuePostingFailureEntity.STATUS_POISON -> poison += count;
                case RevenuePostingFailureEntity.STATUS_ABANDONED -> abandoned += count;
                case RevenuePostingFailureEntity.STATUS_REPLAYED -> replayed += count;
                default -> {
                    // A status the DB CHECK allows but this code does not know: counted in the buckets so it
                    // is visible, but never folded into a headline it might not belong in.
                }
            }
            boolean outstandingBucket =
                    RevenuePostingFailureEntity.STATUS_PENDING.equals(status)
                            || RevenuePostingFailureEntity.STATUS_POISON.equals(status);
            if (outstandingBucket && oldestAt != null
                    && (oldestOutstanding == null || oldestAt.isBefore(oldestOutstanding))) {
                oldestOutstanding = oldestAt;
            }
        }

        return new RevenuePostingOutstandingView(pending + poison, pending, poison, abandoned, replayed,
                oldestOutstanding, List.copyOf(buckets));
    }

    /**
     * Defensive conversion of an aggregate's timestamp. Hibernate 6 returns {@link Instant} for
     * {@code min()} over an {@code Instant} attribute, but the JDBC driver can surface a
     * {@link java.sql.Timestamp} depending on dialect — and an operator report is not worth a
     * {@code ClassCastException}.
     */
    private static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt.toInstant(java.time.ZoneOffset.UTC);
        }
        return null;
    }
}
