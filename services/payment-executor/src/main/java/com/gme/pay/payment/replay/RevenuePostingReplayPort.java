package com.gme.pay.payment.replay;

/**
 * The outbound seam the replay job posts through (gap <b>T2-5</b>).
 *
 * <p>Why this is NOT {@code RevenueLedgerClient}: that client exists to SWALLOW failures, by design — a
 * revenue-ledger outage must never fail a payment whose money already moved. A replay needs the exact
 * opposite contract. It has to know whether the posting landed, so it can decide between "mark REPLAYED",
 * "back off and try again" and "stop and alert". Reusing the swallowing client would make every replay look
 * like a success and quietly clear the table without the money reaching the ledger — the same class of silent
 * loss this gap is about.
 *
 * <p>It also posts the STORED JSON payload verbatim rather than re-deriving a request from typed arguments.
 * The payload captured at failure time is what the money path actually tried to send; rebuilding it would
 * risk replaying something subtly different from what was lost.
 */
public interface RevenuePostingReplayPort {

    /**
     * Re-POST one previously-failed posting.
     *
     * @param postingType one of the {@code RevenuePostingFailureStore.TYPE_*} constants; selects the endpoint
     * @param jsonPayload the exact request body captured when the posting failed
     * @return the classified outcome — never null, never throws
     */
    RevenuePostingReplayOutcome replay(String postingType, String jsonPayload);
}
