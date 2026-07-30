package com.gme.pay.payment.metrics;

import com.gme.pay.errors.ApiError;
import com.gme.pay.payment.web.dto.WalletPaymentResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Service-level indicators for the payment entry points (gap <b>T3-5</b>).
 *
 * <h2>What was missing and why it matters</h2>
 *
 * <p>T3-2 put a Micrometer registry and {@code /actuator/prometheus} on all 20 deployables, but
 * nothing on the money path <em>emitted</em> anything: as
 * {@code RUNBOOK_LOAD_AND_CAPACITY.md} §7.6 records, there was no approval/decline metric, no
 * per-entry-point latency, and no queue-depth signal anywhere. That is exactly the set an SLA
 * conversation runs on — you cannot commit to a latency you do not record, and you cannot size
 * for a volume you cannot count. This class supplies it, and deliberately supplies only it.
 *
 * <h2>Design choices</h2>
 *
 * <ul>
 *   <li><b>One Micrometer registry, no second metrics system.</b> These meters land in the same
 *       registry the platform already exposes, so a load run, a Grafana panel and an alert rule
 *       all read the same series.</li>
 *   <li><b>A decline is not an error.</b> The same distinction the load harness draws
 *       (§2.2 of the runbook): the platform correctly refusing money is normal operation, and
 *       folding it into an error rate would make a healthy high-decline period look like an
 *       outage — and hide a real one behind an expected decline rate. Three outcomes, always:
 *       {@code approved}, {@code declined}, {@code error}.</li>
 *   <li><b>Bounded tag cardinality.</b> {@code entry} is a fixed set of three. {@code outcome} is
 *       three. {@code reason} comes from the platform's own {@link ApiError} codes and decline
 *       reasons, which are enum-derived, and is normalised and length-capped here so that a
 *       free-text message can never blow up the series count. Nothing partner-scoped or
 *       merchant-scoped is tagged: that would multiply every series by the partner count, and
 *       per-partner SLIs are a separate, deliberate decision (runbook §7.4).</li>
 *   <li><b>Percentile histogram, not client-side percentiles.</b> {@code publishPercentileHistogram}
 *       emits Prometheus buckets, so p95/p99 are computed at query time over any window and are
 *       aggregatable across replicas. Pre-computed percentiles are not aggregatable and would be
 *       wrong the moment there is more than one instance.</li>
 * </ul>
 *
 * <h2>What this does NOT do</h2>
 *
 * <p>It declares no target. What latency or availability GMEPay+ owes a partner is a business
 * commitment, and {@code Documentation/SLO_TARGETS.properties} ships with every value blank for
 * that reason. This class makes the numbers <em>measurable</em>; someone with the authority to
 * promise them still has to write them down.
 */
@Component
public class PaymentSliMetrics {

    /** {@code POST /v1/pay} — the wallet scan-to-pay path. */
    public static final String ENTRY_WALLET_PAY = "wallet_pay";

    /** {@code POST /v1/payments/authorize} — phase 1 of the two-phase flow. */
    public static final String ENTRY_AUTHORIZE = "authorize";

    /** {@code POST /v1/payments/{id}/confirm} — phase 2, the leg that calls the scheme. */
    public static final String ENTRY_CONFIRM = "confirm";

    static final String TIMER_NAME = "gmepay.payment.duration";
    static final String COUNTER_NAME = "gmepay.payment.outcome";

    static final String OUTCOME_APPROVED = "approved";
    static final String OUTCOME_DECLINED = "declined";
    static final String OUTCOME_ERROR = "error";

    /** Cap on a tag value's length, so an unexpected free-text reason cannot explode cardinality. */
    static final int MAX_REASON_LENGTH = 48;

    static final String REASON_NONE = "none";
    static final String REASON_UNCLASSIFIED = "unclassified";

    @Nullable
    private final MeterRegistry meters;

    /**
     * @param meters nullable so that unit slices which build the controllers without an actuator
     *               context still work. A missing registry means "not measured", never a crash on
     *               the payment path — an SLI that can fail a payment is worse than no SLI.
     */
    public PaymentSliMetrics(@Nullable MeterRegistry meters) {
        this.meters = meters;
    }

    /**
     * Times one payment entry point and records its outcome.
     *
     * <p>The supplier's exception, if any, is rethrown unchanged after being counted as an
     * {@code error} — this wrapper must be invisible to the caller's control flow.
     */
    /*
     * Generic over the RESPONSE type rather than its body type, so one signature serves both a
     * precisely-typed endpoint (ResponseEntity<AuthorizeResponse>) and the wallet endpoint, whose
     * declared return is the wildcard ResponseEntity<?> because it answers with two different
     * bodies. Making it <T> ResponseEntity<T> would not infer against a wildcard and would force a
     * cast at the call site — on the money path, of all places.
     */
    public <R extends ResponseEntity<?>> R record(String entry, Supplier<R> call) {
        if (meters == null) {
            return call.get();
        }
        long startedAt = System.nanoTime();
        String outcome = OUTCOME_ERROR;
        String reason = REASON_UNCLASSIFIED;
        try {
            R response = call.get();
            outcome = outcomeOf(response);
            reason = reasonOf(outcome, response);
            return response;
        } catch (RuntimeException ex) {
            reason = normalise(ex.getClass().getSimpleName());
            throw ex;
        } finally {
            long elapsedNanos = System.nanoTime() - startedAt;
            timer(entry, outcome).record(elapsedNanos, TimeUnit.NANOSECONDS);
            counter(entry, outcome, reason).increment();
        }
    }

    private Timer timer(String entry, String outcome) {
        return Timer.builder(TIMER_NAME)
                .description("End-to-end server-side duration of a payment entry point")
                .tag("entry", entry)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                // Buckets bracketing the region an SLO would plausibly be written in. Micrometer
                // only emits buckets inside this range, which keeps the series count sane.
                .minimumExpectedValue(Duration.ofMillis(5))
                .maximumExpectedValue(Duration.ofSeconds(30))
                .register(meters);
    }

    private Counter counter(String entry, String outcome, String reason) {
        return Counter.builder(COUNTER_NAME)
                .description("Payment attempts by entry point and outcome "
                        + "(declined = the platform correctly refused; error = the platform failed)")
                .tag("entry", entry)
                .tag("outcome", outcome)
                .tag("reason", reason)
                .register(meters);
    }

    // ------------------------------------------------------------------
    // Classification
    // ------------------------------------------------------------------

    /**
     * Maps a response to one of the three outcomes.
     *
     * <p>A 4xx is a decline and a 5xx is an error, with one deliberate exception: <b>429 is an
     * error</b>. Being rate-limited is structured and intentional, but it means the platform
     * could not accept offered load, which is a capacity failure rather than a business refusal —
     * the same call the load harness makes.
     */
    static String outcomeOf(@Nullable ResponseEntity<?> response) {
        if (response == null) {
            return OUTCOME_ERROR;
        }
        int status = response.getStatusCode().value();
        if (status >= 200 && status < 300) {
            return OUTCOME_APPROVED;
        }
        if (status == 429 || status >= 500) {
            return OUTCOME_ERROR;
        }
        if (status >= 400) {
            return OUTCOME_DECLINED;
        }
        return OUTCOME_ERROR;
    }

    /**
     * Extracts a bounded reason tag from the response body.
     *
     * <p>Reads the two shapes the payment endpoints actually return — the canonical
     * {@link ApiError} envelope and the wallet endpoint's own
     * {@link WalletPaymentResponse#declineReason()} — because those carry enum-derived codes.
     * Anything else becomes {@code unclassified} rather than being tagged with free text.
     */
    static String reasonOf(String outcome, @Nullable ResponseEntity<?> response) {
        if (OUTCOME_APPROVED.equals(outcome)) {
            return REASON_NONE;
        }
        Object body = response == null ? null : response.getBody();
        if (body instanceof ApiError error) {
            return normalise(error.code());
        }
        if (body instanceof WalletPaymentResponse wallet) {
            return normalise(wallet.declineReason());
        }
        return REASON_UNCLASSIFIED;
    }

    /** Lower-cases, strips whitespace and truncates, so a tag value can never be unbounded. */
    static String normalise(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return REASON_UNCLASSIFIED;
        }
        String cleaned = raw.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
        if (cleaned.isBlank() || "_".equals(cleaned)) {
            return REASON_UNCLASSIFIED;
        }
        return cleaned.length() <= MAX_REASON_LENGTH
                ? cleaned
                : cleaned.substring(0, MAX_REASON_LENGTH);
    }
}
