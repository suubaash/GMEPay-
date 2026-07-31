package com.gme.pay.payment.replay;

/**
 * The classified result of one replay attempt (gap <b>T2-5</b>).
 *
 * <p>The distinction that matters is {@link Kind#PERMANENT_REJECTION} vs {@link Kind#TRANSIENT_FAILURE}:
 * re-POSTing a body revenue-ledger has already rejected as malformed will be rejected identically every time,
 * so burning the whole attempt budget on it delays the alert by hours for no benefit. A permanent rejection
 * poisons the row immediately — the operator is told at once that this posting needs a human, not a retry.
 *
 * @param kind    what happened
 * @param status  the HTTP status observed, or 0 for a transport failure
 * @param detail  human-readable description, stamped onto {@code revenue_posting_failures.last_error}
 */
public record RevenuePostingReplayOutcome(Kind kind, int status, String detail) {

    /** Outcome classes, in the order the replay service branches on them. */
    public enum Kind {

        /** revenue-ledger created the posting (201). The revenue is now on the books. */
        POSTED,

        /**
         * revenue-ledger already had this posting (200/204 on an idempotent endpoint). Either an earlier
         * replay landed it, or the money path succeeded on a later attempt and this row was stale. Treated as
         * success — the goal is "the posting is in the ledger", not "this job put it there".
         */
        ALREADY_PRESENT,

        /**
         * revenue-ledger refused the body and always will (a 4xx that is not 408/429). Poison immediately;
         * retrying cannot change the answer.
         */
        PERMANENT_REJECTION,

        /** A 5xx, timeout, connection failure, 408 or 429 — worth trying again after a backoff. */
        TRANSIENT_FAILURE,

        /** The replay could not even be attempted (unknown posting type, no payload). Poison immediately. */
        UNREPLAYABLE
    }

    public static RevenuePostingReplayOutcome posted(int status) {
        return new RevenuePostingReplayOutcome(Kind.POSTED, status, "posted (HTTP " + status + ")");
    }

    public static RevenuePostingReplayOutcome alreadyPresent(int status) {
        return new RevenuePostingReplayOutcome(Kind.ALREADY_PRESENT, status,
                "already present in revenue-ledger (HTTP " + status + ", idempotent no-op)");
    }

    public static RevenuePostingReplayOutcome permanentRejection(int status, String detail) {
        return new RevenuePostingReplayOutcome(Kind.PERMANENT_REJECTION, status, detail);
    }

    public static RevenuePostingReplayOutcome transientFailure(int status, String detail) {
        return new RevenuePostingReplayOutcome(Kind.TRANSIENT_FAILURE, status, detail);
    }

    public static RevenuePostingReplayOutcome unreplayable(String detail) {
        return new RevenuePostingReplayOutcome(Kind.UNREPLAYABLE, 0, detail);
    }

    /** True when the posting is now in revenue-ledger, however it got there. */
    public boolean landed() {
        return kind == Kind.POSTED || kind == Kind.ALREADY_PRESENT;
    }

    /** True when no further attempt could ever succeed. */
    public boolean permanent() {
        return kind == Kind.PERMANENT_REJECTION || kind == Kind.UNREPLAYABLE;
    }
}
