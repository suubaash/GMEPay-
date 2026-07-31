package com.gme.pay.e2e.load;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Load / soak harness for the GMEPay+ money path — gap register <b>T3-5</b>
 * ("no SLA measurement, no load test, no capacity plan"), COO audit §9.
 *
 * <p><b>It does not start the fleet, and it will not start one.</b> Booting 20 JVMs is
 * {@code run-fleet.ps1}'s job (or docker compose); a load generator that also owned the lifecycle of
 * its target would measure cold JIT and Flyway migrations as if they were payment latency, and would
 * make "the fleet was already unhealthy" indistinguishable from "the load broke it". So the harness
 * <b>preflights</b> the fleet, and refuses with the exact command to run if a target is not answering.
 *
 * <p><b>It refuses to run against anything but a local/dev target.</b> See {@link LoadTargetGuard}: all
 * URLs must be local AND {@value LoadTargetGuard#ACK_FLAG} must be present. Both, not either.
 *
 * <p><b>It never runs in CI.</b> There is no test class here and no tag to include — the entry point is
 * a {@code main} reachable only through the explicit {@code :e2e-tests:loadTest} JavaExec task, which
 * no workflow in {@code .github/workflows/} invokes ({@code ci.yml} runs {@code build},
 * {@code integrationTest} and {@code :e2e-tests:e2eTest}). {@code scripts/check_load_harness_wiring.py}
 * pins that.
 *
 * <h2>Load model</h2>
 * <b>Open model with explicit shedding.</b> Arrivals are scheduled at fixed intervals from a monotonic
 * baseline ({@code start + i/rate}), not "sleep between requests" — the latter is a closed model whose
 * offered rate silently drops as the system slows, hiding exactly the degradation being looked for.
 * When {@code --concurrency} in-flight requests are already outstanding the arrival is <b>SHED and
 * counted</b>, never queued: a queued arrival would inflate the next request's measured latency with
 * client-side waiting (coordinated omission) and report the harness's own backlog as the platform's.
 * A non-zero shed count is therefore a first-class finding, printed in the summary.
 *
 * <p>One virtual thread per request ({@code Executors.newVirtualThreadPerTaskExecutor()}, Java 21 —
 * the toolchain the whole repo already targets), so the harness itself is not a thread-count ceiling.
 */
public final class LoadHarness {

    private LoadHarness() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && (args[0].equals("--help") || args[0].equals("-h"))) {
            System.out.println(LoadOptions.usage());
            return;
        }

        LoadOptions opts;
        try {
            opts = LoadOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("[load] " + e.getMessage());
            System.err.println();
            System.err.println(LoadOptions.usage());
            System.exit(2);
            return;
        }

        // ---- SAFETY INTERLOCK: before any socket is opened. ----
        try {
            LoadTargetGuard.requireLocalDevTarget(opts.allTargets(), opts.acknowledged);
        } catch (LoadTargetGuard.RefusedException e) {
            System.err.println("[load] " + e.getMessage());
            System.err.println();
            System.err.println("Nothing was sent. This harness drives a REAL money path (it creates "
                    + "authorizations and moves prefunded float) and only ever runs against a local fleet.");
            System.exit(3);
            return;
        }

        Scenario scenario = switch (opts.scenario) {
            case WALLET_PAY -> new Scenario.WalletPay(opts);
            case AUTHORIZE_CONFIRM -> new Scenario.AuthorizeConfirm(opts);
        };

        HttpClient probeClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        if (!preflight(probeClient, scenario, opts)) {
            System.exit(4);
            return;
        }

        SloTargets targets = SloTargets.load(opts.targetsFile);
        System.out.println("[load] SLO targets: " + targets.describe());

        // ---- before-scrape ----
        System.out.println("[load] scraping /actuator/prometheus (before) ...");
        PrometheusSnapshot before = scrape(probeClient, opts);

        // ---- warm-up (discarded) ----
        if (!opts.warmup.isZero()) {
            System.out.printf("[load] warm-up %ds at %.1f req/s (results discarded)%n",
                    opts.warmup.toSeconds(), opts.rate);
            drive(scenario, opts, opts.warmup, new Results());
        }

        // ---- measured window ----
        System.out.printf("[load] measuring %ds at %.1f req/s, concurrency %d, scenario '%s'%n",
                opts.duration.toSeconds(), opts.rate, opts.concurrency, scenario.name());
        Results results = new Results();
        Instant runStarted = Instant.now();
        long elapsedNs = drive(scenario, opts, opts.duration, results);
        Instant runEnded = Instant.now();

        // ---- after-scrape ----
        System.out.println("[load] scraping /actuator/prometheus (after) ...");
        PrometheusSnapshot after = scrape(probeClient, opts);

        LoadReport report = new LoadReport(opts, scenario.name(), runStarted, runEnded, elapsedNs,
                results, before, after, targets);
        report.write();
        System.out.println();
        System.out.println(report.humanSummary());

        // Exit code carries the verdict so a wrapper script can gate on it:
        //   0 = declared targets all met, OR nothing declared (which is NOT a pass — see the summary)
        //   5 = a declared target was missed
        System.exit(report.evaluation().verdict() == SloTargets.Verdict.FAIL ? 5 : 0);
    }

    // -------------------------------------------------------------------------
    // Preflight — the fleet must already be up; we never start it
    // -------------------------------------------------------------------------

    private static boolean preflight(HttpClient http, Scenario scenario, LoadOptions opts) {
        List<String> dead = new ArrayList<>();
        List<String> urls = new ArrayList<>(scenario.preflightUrls());
        for (String url : urls) {
            // "Up" = the port answers HTTP with any status — the same heuristic SchemeFleet and
            // run-fleet.ps1 use, so a 401 from a gated probe still counts as alive.
            try {
                HttpResponse<Void> r = http.send(
                        HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (r.statusCode() <= 0) {
                    dead.add(url);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                dead.add(url + " (interrupted)");
            } catch (Exception e) {
                dead.add(url + " (" + e.getClass().getSimpleName() + ")");
            }
        }
        if (!dead.isEmpty()) {
            System.err.println("[load] PREFLIGHT FAILED - these fleet endpoints are not answering:");
            dead.forEach(d -> System.err.println("         " + d));
            System.err.println();
            System.err.println("This harness does not start the fleet. Start it first, then re-run:");
            System.err.println("    powershell -File run-fleet.ps1 -Build");
            System.err.println("(or bring up the docker-compose 'core' profile). Nothing was sent.");
            return false;
        }
        System.out.println("[load] preflight OK - " + urls.size() + " endpoint(s) answering");
        return true;
    }

    // -------------------------------------------------------------------------
    // The driver
    // -------------------------------------------------------------------------

    /** Runs one window and returns its measured wall-clock nanos. */
    private static long drive(Scenario scenario, LoadOptions opts, Duration window, Results results) {
        long intervalNs = (long) (1_000_000_000L / opts.rate);
        Semaphore inFlight = new Semaphore(opts.concurrency);
        long startNs = System.nanoTime();
        long windowNs = window.toNanos();
        int iteration = 0;

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            while (true) {
                long targetNs = startNs + (long) iteration * intervalNs;
                if (targetNs - startNs >= windowNs) {
                    break;
                }
                parkUntil(targetNs);
                final int current = iteration++;

                // tryAcquire with NO timeout: the arrival is either served now or shed now. Waiting here
                // is what produces coordinated omission.
                if (!inFlight.tryAcquire()) {
                    results.record(Outcome.shed());
                    continue;
                }
                pool.submit(() -> {
                    try {
                        results.record(scenario.runOnce(current));
                    } catch (Throwable t) {
                        // A scenario must not throw; if one does, it is recorded rather than lost, so the
                        // run's attempt count stays equal to its arrival count.
                        results.record(Outcome.error(
                                "HARNESS_" + t.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT),
                                0, 0L));
                    } finally {
                        inFlight.release();
                    }
                });
            }
            // Let the tail drain: in-flight requests must finish or we would discard the slowest samples,
            // which are precisely the ones a p99 is made of.
            pool.shutdown();
            long drainSeconds = Math.max(30, opts.requestTimeout.toSeconds() * 2);
            if (!pool.awaitTermination(drainSeconds, TimeUnit.SECONDS)) {
                System.err.println("[load] WARNING: requests still in flight after " + drainSeconds
                        + "s drain - their samples are missing from the percentiles below");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return System.nanoTime() - startNs;
    }

    /** Precise-enough pacing without burning a core: park, do not spin. */
    private static void parkUntil(long targetNs) {
        long remaining = targetNs - System.nanoTime();
        while (remaining > 0) {
            LockSupport.parkNanos(remaining);
            remaining = targetNs - System.nanoTime();
        }
    }

    // -------------------------------------------------------------------------
    // Scraping
    // -------------------------------------------------------------------------

    /**
     * Scrapes each configured service's {@code /actuator/prometheus}, presenting the internal token.
     *
     * <p>A failed scrape is <b>recorded, not fatal</b> — and the distinction matters. Per
     * {@code RUNBOOK_MONITORING.md} §1.3 the endpoint is gated on api-gateway / payment-executor /
     * prefunding / rate-fx / scheme-adapter-zeropay and anonymous on twelve others, so a 401 here means
     * "wrong token", not "no metrics"; whereas a connection failure on a service that just passed
     * preflight is itself a finding. Both are surfaced in the report instead of aborting a run that has
     * already generated real load.
     */
    private static PrometheusSnapshot scrape(HttpClient http, LoadOptions opts) {
        if (opts.scrapeTargets.isEmpty()) {
            return PrometheusSnapshot.empty();
        }
        PrometheusSnapshot.Builder builder = PrometheusSnapshot.builder();
        for (Map.Entry<String, String> target : opts.scrapeTargets.entrySet()) {
            String url = target.getValue() + "/actuator/prometheus";
            try {
                HttpResponse<String> resp = http.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(10))
                                .header(Scenario.INTERNAL_HEADER, opts.internalSecret)
                                .GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    builder.service(target.getKey(), resp.body());
                } else {
                    builder.failed(target.getKey(), "HTTP " + resp.statusCode()
                            + (resp.statusCode() == 401
                            ? " - wrong --internal-secret for this fleet (RUNBOOK_MONITORING §1.3)" : ""));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                builder.failed(target.getKey(), "interrupted");
            } catch (Exception e) {
                builder.failed(target.getKey(), e.getClass().getSimpleName());
            }
        }
        return builder.build();
    }

    // -------------------------------------------------------------------------
    // Accumulator
    // -------------------------------------------------------------------------

    /** Thread-safe tallies for one window. */
    static final class Results {
        final Latencies overall = new Latencies();
        /** step name → latencies, for the multi-step scenario. */
        final Map<String, Latencies> perStep = new ConcurrentHashMap<>();
        /** structured code → count, for every non-OK outcome. */
        final Map<String, AtomicLong> codeCounts = new ConcurrentHashMap<>();
        final AtomicLong attempted = new AtomicLong();
        final AtomicLong ok = new AtomicLong();
        final AtomicLong declined = new AtomicLong();
        final AtomicLong errored = new AtomicLong();
        final AtomicLong shed = new AtomicLong();

        void record(Outcome outcome) {
            attempted.incrementAndGet();
            switch (outcome.kind()) {
                case OK -> {
                    ok.incrementAndGet();
                    overall.record(outcome.latencyNs());
                    String[] names = outcome.stepNames();
                    long[] steps = outcome.stepNs();
                    for (int i = 0; i < names.length && i < steps.length; i++) {
                        perStep.computeIfAbsent(names[i], k -> new Latencies()).record(steps[i]);
                    }
                }
                case DECLINED -> {
                    declined.incrementAndGet();
                    count(outcome.code());
                    // A decline is a completed round trip, so its latency IS part of the platform's
                    // observed response time. It is recorded, but the success-rate maths keeps it out of
                    // `ok` — see SloTargets.evaluate for why that split is an owner decision.
                    overall.record(outcome.latencyNs());
                }
                case ERROR -> {
                    errored.incrementAndGet();
                    count(outcome.code());
                    // Deliberately NOT recorded into the percentiles: a 3ms connection-refused would drag
                    // p50 down and make a broken run look fast. Errors are counted, not timed.
                }
                case SHED -> {
                    shed.incrementAndGet();
                    count(outcome.code());
                }
            }
        }

        private void count(String code) {
            codeCounts.computeIfAbsent(code, k -> new AtomicLong()).incrementAndGet();
        }

        /** code → count, highest first, for the report. */
        Map<String, Long> codeTally() {
            Map<String, Long> out = new LinkedHashMap<>();
            codeCounts.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                    .forEach(e -> out.put(e.getKey(), e.getValue().get()));
            return out;
        }
    }
}
