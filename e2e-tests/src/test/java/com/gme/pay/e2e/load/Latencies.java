package com.gme.pay.e2e.load;

import java.util.Arrays;

/**
 * Exact latency percentiles from a growable {@code long[]} of every recorded sample.
 *
 * <p><b>Why exact and not a sketch.</b> A load run of this shape produces thousands of samples, not
 * millions — 8 bytes each, so the whole run fits in well under a megabyte. A t-digest or HDR histogram
 * would add a dependency and an approximation error to save memory nobody needs, and the p99 is the
 * number an SLO gets written against: it should not be an estimate when it does not have to be.
 *
 * <p>Not thread-safe by design — {@link #record(long)} is called under the recorder's monitor in
 * {@link LoadHarness}, and lock-striping thousands of appends would be complexity for nothing.
 */
final class Latencies {

    private long[] samples = new long[1024];
    private int size;

    synchronized void record(long nanos) {
        if (size == samples.length) {
            samples = Arrays.copyOf(samples, samples.length * 2);
        }
        samples[size++] = nanos;
    }

    synchronized int count() {
        return size;
    }

    /**
     * Nearest-rank percentile (no interpolation) over a defensive copy, in milliseconds.
     *
     * <p>Nearest-rank is chosen deliberately: it always returns a value that was actually observed, so
     * "p99 = 812ms" means some request really took 812ms. Interpolated percentiles can report a
     * latency no request ever had, which is a poor basis for a contractual commitment.
     *
     * @return {@code Double.NaN} when there are no samples — a caller must render that as "n/a", never
     *         as 0, because 0ms would read as "instant" rather than "not measured"
     */
    synchronized double percentileMs(double percentile) {
        if (size == 0) {
            return Double.NaN;
        }
        long[] sorted = Arrays.copyOf(samples, size);
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(percentile / 100.0 * size) - 1;
        rank = Math.max(0, Math.min(size - 1, rank));
        return sorted[rank] / 1_000_000.0;
    }

    synchronized double minMs() {
        return size == 0 ? Double.NaN : percentileMs(0.0);
    }

    synchronized double maxMs() {
        return size == 0 ? Double.NaN : percentileMs(100.0);
    }

    synchronized double meanMs() {
        if (size == 0) {
            return Double.NaN;
        }
        double total = 0;
        for (int i = 0; i < size; i++) {
            total += samples[i];
        }
        return total / size / 1_000_000.0;
    }
}
