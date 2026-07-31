package com.gme.pay.payment.replay;

import java.time.Instant;
import java.util.List;

/**
 * "What revenue is still missing from the ledger?" — the ops query gap <b>T2-5</b> asked for.
 *
 * <p>The headline is {@link #outstandingCount()}: postings that are recorded as earned but are NOT on the
 * ledger, i.e. PENDING (still being retried) plus POISON (given up on). REPLAYED and ABANDONED rows are
 * counted separately and deliberately excluded from it — one landed, the other was a human's decision.
 *
 * <p>{@code oldestOutstandingAt} is the number that matters operationally: a handful of postings retrying for
 * ten minutes is a blip, the same handful retrying since last Tuesday is an incident that the count alone would
 * not distinguish.
 *
 * @param outstandingCount   PENDING + POISON — postings whose money is not on the ledger
 * @param pendingCount        awaiting or between replay attempts
 * @param poisonCount         given up on by the machine (each one alerted when it got there)
 * @param abandonedCount      given up on by an operator
 * @param replayedCount       successfully landed in revenue-ledger
 * @param oldestOutstandingAt when the oldest outstanding posting first failed; null when none are outstanding
 * @param buckets             per-status, per-posting-type breakdown with each bucket's oldest row
 */
public record RevenuePostingOutstandingView(
        long outstandingCount,
        long pendingCount,
        long poisonCount,
        long abandonedCount,
        long replayedCount,
        Instant oldestOutstandingAt,
        List<Bucket> buckets
) {

    /**
     * One (status, postingType) bucket.
     *
     * @param status      row status
     * @param postingType which revenue-ledger surface the posting was aimed at
     * @param count       rows in this bucket
     * @param oldestAt    when the oldest row in this bucket first failed
     */
    public record Bucket(String status, String postingType, long count, Instant oldestAt) {}
}
