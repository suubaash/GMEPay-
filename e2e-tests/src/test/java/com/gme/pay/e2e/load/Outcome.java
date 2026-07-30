package com.gme.pay.e2e.load;

/**
 * One attempted money-path iteration.
 *
 * <p>The three-way split is the point of this record. Before the platform had structured error codes
 * a load run could only report "non-2xx %", which conflates <em>the platform correctly refusing
 * money</em> with <em>the platform falling over</em> — and at 10x volume the first is expected and the
 * second is the finding. So:
 *
 * <ul>
 *   <li>{@link Kind#OK} — 2xx, the payment completed.</li>
 *   <li>{@link Kind#DECLINED} — a 4xx carrying a structured code the platform owns
 *       ({@code TRANSACTION_LIMIT_EXCEEDED}, {@code SCHEME_CLOSED},
 *       {@code SCHEME_OPERATION_UNSUPPORTED}, {@code MERCHANT_INACTIVE}, …). Working as designed;
 *       counted and broken down by code, but NOT counted as an error.</li>
 *   <li>{@link Kind#ERROR} — 5xx, a timeout, a connection failure, or a 4xx with no recognisable
 *       code. This is the number an SLO is written against.</li>
 *   <li>{@link Kind#SHED} — the harness itself hit its {@code --concurrency} cap and never sent the
 *       request. Reported separately and loudly: it means the run did not achieve the requested rate,
 *       so every latency percentile below it is measured on a smaller load than intended
 *       (coordinated omission, named rather than hidden).</li>
 * </ul>
 *
 * @param kind       classification
 * @param code       structured error/decline code, or {@code HTTP_<status>} / {@code TIMEOUT} /
 *                   {@code TRANSPORT} when the response carried none. Never null for a non-OK kind.
 * @param httpStatus HTTP status, or 0 when no response was received
 * @param latencyNs  wall-clock nanos from first byte sent to last byte received, across ALL steps of
 *                   the scenario (the end-to-end number a partner would feel). 0 for {@link Kind#SHED}.
 * @param stepNs     per-step nanos (e.g. quote / authorize / confirm), same order the steps ran
 * @param stepNames  labels for {@link #stepNs}
 */
public record Outcome(Kind kind,
                      String code,
                      int httpStatus,
                      long latencyNs,
                      long[] stepNs,
                      String[] stepNames) {

    public enum Kind { OK, DECLINED, ERROR, SHED }

    private static final long[] NO_STEPS = new long[0];
    private static final String[] NO_NAMES = new String[0];

    public static Outcome ok(long latencyNs, long[] stepNs, String[] stepNames) {
        return new Outcome(Kind.OK, "OK", 200, latencyNs, stepNs, stepNames);
    }

    public static Outcome declined(String code, int httpStatus, long latencyNs) {
        return new Outcome(Kind.DECLINED, code, httpStatus, latencyNs, NO_STEPS, NO_NAMES);
    }

    public static Outcome error(String code, int httpStatus, long latencyNs) {
        return new Outcome(Kind.ERROR, code, httpStatus, latencyNs, NO_STEPS, NO_NAMES);
    }

    public static Outcome shed() {
        return new Outcome(Kind.SHED, "CONCURRENCY_CAP", 0, 0L, NO_STEPS, NO_NAMES);
    }
}
