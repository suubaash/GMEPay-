package com.gme.pay.notify.dispatcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * T3-11 defect 3: <b>the webhook drain's sustained ceiling must exceed the payment rate the platform
 * itself admits, with headroom.</b>
 *
 * <h2>Why this test exists at all</h2>
 *
 * <p>The register line for this defect is "the webhook drain does not keep up even at 1×", and the
 * first pass at it raised concurrency 1 → 8, shortened the cycle and halved the per-delivery timeout —
 * then reported the improvement as a multiple ("roughly 16×") rather than as a rate. A multiple cannot
 * answer the question the defect asks. 16× a rate that was three orders below the payment rate is still
 * below the payment rate, and {@code WebhookDrainCapacityTest} — which proves the batch no longer
 * serialises — cannot tell the difference either, because it measures the drain against ITSELF.
 *
 * <p>Redoing the arithmetic against an absolute rate showed the pipeline still did not close:
 * {@code ceil(200/8) = 25} waves × 250 ms = 6.25 s of delivering, plus a 5 s {@code fixedDelay} of
 * deliberately doing nothing, = 17.8 deliveries/s against a gateway that admits 50 payments/s. So the
 * drain fell behind at a rate the platform's own edge permits, and would have kept doing so.
 *
 * <h2>The rate it is measured against</h2>
 *
 * <p>{@code api-gateway}'s {@code rate-limit.payments-per-second}. This is deliberately NOT a number
 * invented here: the platform declares no throughput SLO ({@code Documentation/SLO_TARGETS.properties}
 * ships blank on purpose — T3-5 §3, because a number nobody owns is worse than an absent one), so the
 * only payment rate the repository actually commits to is what the edge will admit. Ceiling versus
 * ceiling is the right comparison for a queue: if the gateway will accept 50 payments/s then the
 * pipeline behind it must be able to emit more than 50 deliveries/s, or the backlog grows without bound
 * inside the platform's own stated limits.
 *
 * <p>The test reads that number out of {@code api-gateway}'s shipped YAML rather than restating it, so
 * raising the admission ceiling without re-sizing the drain fails here.
 *
 * <h2>What it does not claim</h2>
 *
 * <p>This is arithmetic over shipped configuration, not a measurement. It has no partner endpoint in it
 * and no network. {@link #ASSUMED_PARTNER_LATENCY_MS} is an assumption, named as one; the worst case
 * (every endpoint at its full read timeout) is far lower and is not fixable by sizing — that case is
 * what the per-endpoint circuit breaker exists for. What this test does guarantee is that the four
 * numbers which decide the ceiling can never again drift into a combination that sits below the
 * admission rate without someone being told.
 */
class WebhookDrainThroughputTest {

    /**
     * The per-delivery latency the capacity arithmetic is quoted at. An engineering assumption, not a
     * partner SLA and not measured anywhere: it is the time a partner endpoint takes to accept a small
     * signed JSON POST. Stated as a constant so a reader can substitute their own estimate and redo the
     * sum, rather than having to reverse-engineer which latency a claimed rate assumed.
     */
    private static final long ASSUMED_PARTNER_LATENCY_MS = 250L;

    /**
     * Required headroom over the admission ceiling. 2× rather than 1.1×, because a queue that drains at
     * exactly its arrival rate never recovers from a backlog — it only stops growing. Headroom IS the
     * catch-up capacity, and every real deployment needs some: a partner outage parks rows that all
     * become due at once when the breaker closes.
     */
    private static final double REQUIRED_HEADROOM = 2.0d;

    @Test
    @DisplayName("the shipped drain out-paces the gateway's admitted payment rate with 2x headroom")
    void shippedDrainCeilingExceedsTheAdmittedPaymentRate() throws IOException {
        Properties shipped = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application.properties"));

        int batchSize = shippedInt(shipped, "gmepay.webhook.dispatcher.batch-size");
        int concurrency = shippedInt(shipped, "gmepay.webhook.dispatcher.concurrency");
        long intervalMs = shippedInt(shipped, "gmepay.webhook.dispatcher.interval-ms");

        // ceil(batch / concurrency) waves, each costing one partner round trip. The drain waits for
        // every in-flight delivery before returning (see WebhookDispatcher#dispatchConcurrently), and
        // fixedDelay then adds the interval as dead time on top — which is why the interval belongs in
        // this sum and not beside it.
        long waves = (batchSize + concurrency - 1L) / concurrency;
        long cycleMs = waves * ASSUMED_PARTNER_LATENCY_MS + intervalMs;
        double deliveriesPerSecond = batchSize * 1000.0d / cycleMs;

        double admittedPaymentsPerSecond = gatewayAdmittedPaymentsPerSecond();

        assertThat(deliveriesPerSecond)
                .as("""
                        the drain must out-pace the payment rate the api-gateway will admit \
                        (%.1f/s), with %.1fx headroom to recover a backlog rather than merely \
                        stop adding to it. Shipped: batch-size=%d, concurrency=%d, interval=%dms \
                        -> %d waves x %dms + %dms = %dms/cycle -> %.1f deliveries/s. \
                        Raise concurrency and/or batch-size, or shorten the interval — and raise \
                        spring.datasource.hikari.maximum-pool-size with concurrency."""
                        .formatted(admittedPaymentsPerSecond, REQUIRED_HEADROOM, batchSize,
                                concurrency, intervalMs, waves, ASSUMED_PARTNER_LATENCY_MS,
                                intervalMs, cycleMs, deliveriesPerSecond))
                .isGreaterThanOrEqualTo(admittedPaymentsPerSecond * REQUIRED_HEADROOM);
    }

    @Test
    @DisplayName("the connection pool is sized above the worker concurrency, not below it")
    void hikariPoolIsSizedAboveTheDrainConcurrency() throws IOException {
        Properties shipped = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application.properties"));

        int concurrency = shippedInt(shipped, "gmepay.webhook.dispatcher.concurrency");
        int pool = shippedInt(shipped, "spring.datasource.hikari.maximum-pool-size");

        // Every worker makes a short DB write after its HTTP call returns. Under-sizing the pool does
        // not slow the drain down gracefully — it moves the queue out of the dispatcher, where the
        // queue-depth alert can see it, and into a pool the HTTP API shares, where it surfaces as
        // unexplained API latency instead. Strictly greater, so the API always has connections left
        // while a full batch is draining.
        assertThat(pool)
                .as("HikariCP's default is 10; %d drain workers would queue on the pool and push the "
                        + "backlog into the API's connection budget", concurrency)
                .isGreaterThan(concurrency);
    }

    @Test
    @DisplayName("the ShedLock window still covers the worst-case drain after re-sizing")
    void lockWindowStillCoversTheWorstCaseDrain() throws IOException {
        Properties shipped = PropertiesLoaderUtils.loadProperties(
                new ClassPathResource("application.properties"));

        int batchSize = shippedInt(shipped, "gmepay.webhook.dispatcher.batch-size");
        int concurrency = shippedInt(shipped, "gmepay.webhook.dispatcher.concurrency");
        long readTimeoutMs = shippedInt(shipped, "gmepay.webhook.http.read-timeout-millis");

        // Worst case: every delivery in the batch sits until its read timeout.
        long waves = (batchSize + concurrency - 1L) / concurrency;
        long worstCaseDrainMs = waves * readTimeoutMs;

        // Raising batch-size raises the worst-case drain, and a lock that expires MID-DRAIN lets a
        // second replica select rows the first is still delivering — the exact duplicate the lock
        // exists to prevent. So this has to be re-checked whenever the batch grows.
        long lockAtMostForMs = isoDurationMillis(defaultOf(
                shipped.getProperty("gmepay.webhook.dispatcher.lock-at-most-for", "PT10M")));

        assertThat(lockAtMostForMs)
                .as("lockAtMostFor (%d ms) must exceed the worst-case drain (%d waves x %d ms = "
                        + "%d ms), or a second replica starts while the first is still delivering",
                        lockAtMostForMs, waves, readTimeoutMs, worstCaseDrainMs)
                .isGreaterThan(worstCaseDrainMs);
    }

    private static double gatewayAdmittedPaymentsPerSecond() throws IOException {
        Path gatewayConfig = repositoryRoot()
                .resolve("services/api-gateway/src/main/resources/application.yml");
        String yaml = Files.readString(gatewayConfig);
        Matcher matcher = Pattern.compile("payments-per-second:\\s*(\\d+)").matcher(yaml);
        assertThat(matcher.find())
                .as("could not read rate-limit.payments-per-second from %s — if the gateway's "
                        + "admission ceiling moved, this test's fixture has to move with it",
                        gatewayConfig)
                .isTrue();
        return Double.parseDouble(matcher.group(1));
    }

    /** Reads an int property, unwrapping a {@code ${ENV_VAR:default}} placeholder. */
    private static int shippedInt(Properties shipped, String key) {
        String raw = shipped.getProperty(key);
        assertThat(raw).as("%s must be stated in the shipped configuration, not left to a "
                + "constructor default nobody reviews", key).isNotNull();
        return Integer.parseInt(defaultOf(raw));
    }

    private static String defaultOf(String raw) {
        return raw.replaceAll("^\\$\\{[^:]+:", "").replaceAll("}$", "").trim();
    }

    /** Minimal ISO-8601 duration parse for the {@code PTnM} / {@code PTnS} forms used here. */
    private static long isoDurationMillis(String iso) {
        return java.time.Duration.parse(iso).toMillis();
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.exists(candidate.resolve("settings.gradle"))
                    && Files.isDirectory(candidate.resolve("services"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("could not locate the repository root above "
                + Path.of("").toAbsolutePath());
    }
}
