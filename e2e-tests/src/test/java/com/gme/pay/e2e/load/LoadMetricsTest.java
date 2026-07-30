package com.gme.pay.e2e.load;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The measurement side: percentiles, decline-vs-error classification, the Prometheus parse, and CLI
 * parsing. Untagged, so it runs on every {@code gradlew build} — these are the numbers a capacity
 * decision would be made from, and a silently wrong p99 is worse than no p99.
 */
@DisplayName("Load harness measurement")
class LoadMetricsTest {

    @Nested
    @DisplayName("Latencies")
    class LatencyPercentiles {

        @Test
        @DisplayName("no samples → NaN, never 0 (0ms would read as 'instant', not 'not measured')")
        void noSamplesIsNaN() {
            Latencies l = new Latencies();
            assertEquals(0, l.count());
            assertTrue(Double.isNaN(l.percentileMs(99)));
            assertTrue(Double.isNaN(l.meanMs()));
            assertTrue(Double.isNaN(l.minMs()));
            assertTrue(Double.isNaN(l.maxMs()));
        }

        @Test
        @DisplayName("nearest-rank percentiles over 1..100 ms")
        void nearestRank() {
            Latencies l = new Latencies();
            for (int ms = 1; ms <= 100; ms++) {
                l.record(ms * 1_000_000L);
            }
            assertEquals(100, l.count());
            assertEquals(50.0, l.percentileMs(50), 0.001);
            assertEquals(95.0, l.percentileMs(95), 0.001);
            assertEquals(99.0, l.percentileMs(99), 0.001);
            assertEquals(1.0, l.minMs(), 0.001);
            assertEquals(100.0, l.maxMs(), 0.001);
            assertEquals(50.5, l.meanMs(), 0.001);
        }

        @Test
        @DisplayName("a percentile is always a value that really happened (no interpolation)")
        void percentileIsAnObservedValue() {
            Latencies l = new Latencies();
            l.record(10_000_000L);   // 10ms
            l.record(1_000_000_000L); // 1000ms
            double p99 = l.percentileMs(99);
            assertTrue(p99 == 10.0 || p99 == 1000.0,
                    "p99 must be an observed sample, got " + p99);
        }

        @Test
        @DisplayName("grows past its initial capacity without losing samples")
        void growsBeyondInitialCapacity() {
            Latencies l = new Latencies();
            for (int i = 0; i < 5000; i++) {
                l.record(1_000_000L);
            }
            assertEquals(5000, l.count());
        }
    }

    @Nested
    @DisplayName("ResponseCodes")
    class Classification {

        @Test
        @DisplayName("2xx → OK")
        void success() {
            assertEquals(Outcome.Kind.OK, ResponseCodes.classify(201, "{\"status\":\"APPROVED\"}", 1).kind());
        }

        @Test
        @DisplayName("a structured 4xx is a DECLINE, not a failure")
        void structuredFourXxIsDecline() {
            Outcome limit = ResponseCodes.classify(422,
                    "{\"code\":\"TRANSACTION_LIMIT_EXCEEDED\",\"retryable\":false}", 1);
            assertEquals(Outcome.Kind.DECLINED, limit.kind());
            assertEquals("TRANSACTION_LIMIT_EXCEEDED", limit.code());

            Outcome closed = ResponseCodes.classify(409, "{\"code\":\"SCHEME_CLOSED\"}", 1);
            assertEquals(Outcome.Kind.DECLINED, closed.kind());
            assertEquals("SCHEME_CLOSED", closed.code());

            Outcome unsupported = ResponseCodes.classify(422,
                    "{\"code\":\"SCHEME_OPERATION_UNSUPPORTED\"}", 1);
            assertEquals(Outcome.Kind.DECLINED, unsupported.kind());
        }

        @Test
        @DisplayName("the wallet endpoint's declineReason envelope is understood too")
        void walletDeclineReason() {
            Outcome outcome = ResponseCodes.classify(422,
                    "{\"status\":\"DECLINED\",\"declineReason\":\"MERCHANT_INACTIVE\"}", 1);
            assertEquals(Outcome.Kind.DECLINED, outcome.kind());
            assertEquals("MERCHANT_INACTIVE", outcome.code());
        }

        @Test
        @DisplayName("an UNSTRUCTURED 4xx is an ERROR — otherwise a broken run looks healthy")
        void unstructuredFourXxIsError() {
            Outcome outcome = ResponseCodes.classify(422, "not json at all", 1);
            assertEquals(Outcome.Kind.ERROR, outcome.kind());
            assertEquals("HTTP_422", outcome.code());
        }

        @Test
        @DisplayName("5xx → ERROR")
        void serverErrorIsError() {
            assertEquals(Outcome.Kind.ERROR, ResponseCodes.classify(503, "", 1).kind());
            assertEquals("HTTP_500", ResponseCodes.classify(500, null, 1).code());
        }

        @Test
        @DisplayName("429 is an ERROR named RATE_LIMITED — being throttled is the capacity finding")
        void rateLimitedIsError() {
            Outcome outcome = ResponseCodes.classify(429, "{\"code\":\"RATE_LIMIT_EXCEEDED\"}", 1);
            assertEquals(Outcome.Kind.ERROR, outcome.kind());
            assertEquals("RATE_LIMITED", outcome.code());
        }

        @Test
        @DisplayName("Spring's default error body is normalised into a code shape")
        void springDefaultBody() {
            assertEquals("UNPROCESSABLE_ENTITY",
                    ResponseCodes.extractCode("{\"error\":\"Unprocessable Entity\",\"status\":422}"));
        }

        @Test
        @DisplayName("a non-JSON body yields no code (the caller falls back to HTTP_<status>)")
        void nonJsonBody() {
            assertNull(ResponseCodes.extractCode("<html>502 Bad Gateway</html>"));
            assertNull(ResponseCodes.extractCode(""));
            assertNull(ResponseCodes.extractCode(null));
        }
    }

    @Nested
    @DisplayName("PrometheusSnapshot")
    class Scrape {

        private static final String EXPOSITION = """
                # HELP hikaricp_connections_pending Pending threads
                # TYPE hikaricp_connections_pending gauge
                hikaricp_connections_pending{application="payment-executor",pool="HikariPool-1",} 3.0
                hikaricp_connections_active{application="payment-executor",pool="HikariPool-1",} 10.0
                hikaricp_connections_max{application="payment-executor",pool="HikariPool-1",} 10.0
                hikaricp_connections_timeout_total{application="payment-executor",pool="HikariPool-1",} 7.0
                jvm_memory_used_bytes{area="heap",id="G1 Eden Space",} 2.097152E7
                jvm_memory_used_bytes{area="heap",id="G1 Old Gen",} 1.048576E7
                jvm_threads_live_threads{application="payment-executor",} 42.0
                http_server_requests_seconds_count{status="200",uri="/v1/pay",} 900.0
                http_server_requests_seconds_count{status="422",uri="/v1/pay",} 100.0
                process_cpu_usage{application="payment-executor",} 0.87
                some_metric_we_do_not_care_about{x="1",} 12345.0
                jvm_gc_pause_seconds_count{action="end of minor GC",} NaN
                """;

        @Test
        @DisplayName("keeps the saturation families and drops everything else")
        void keepsOnlyWhatMatters() {
            Map<String, Double> series = PrometheusSnapshot.parse(EXPOSITION);
            assertTrue(series.keySet().stream().noneMatch(k -> k.startsWith("some_metric")),
                    "unlisted families must be dropped");
            assertFalse(series.isEmpty());
        }

        @Test
        @DisplayName("a counter with one series per (uri,status) is SUMMED, a gauge is MAXed")
        void sumsCountersMaxesGauges() {
            PrometheusSnapshot snap = PrometheusSnapshot.builder()
                    .service("payment-executor", EXPOSITION).build();
            assertEquals(Optional.of(1000.0),
                    snap.familySum("payment-executor", "http_server_requests_seconds_count"));
            assertEquals(Optional.of(3.0),
                    snap.familyMax("payment-executor", "hikaricp_connections_pending"));
            assertEquals(Optional.of(31457280.0),
                    snap.familySum("payment-executor", "jvm_memory_used_bytes"));
        }

        @Test
        @DisplayName("NaN / unparsable / comment lines are skipped, never fatal")
        void tolerantParsing() {
            Map<String, Double> series = PrometheusSnapshot.parse(EXPOSITION);
            assertTrue(series.keySet().stream().noneMatch(k -> k.startsWith("jvm_gc_pause_seconds_count")),
                    "a NaN sample must be dropped, not stored");
            // Garbage in the middle of a scrape must not lose the rest of it.
            Map<String, Double> mixed = PrometheusSnapshot.parse(
                    "garbage\nhikaricp_connections_pending{pool=\"p\",} 5.0\n???");
            assertEquals(1, mixed.size());
        }

        @Test
        @DisplayName("a missing family yields empty, not 0 (absent ≠ zero)")
        void absentIsNotZero() {
            PrometheusSnapshot snap = PrometheusSnapshot.builder()
                    .service("merchant-qr-data", "jvm_threads_live_threads 9.0").build();
            assertTrue(snap.familyMax("merchant-qr-data", "hikaricp_connections_pending").isEmpty(),
                    "a service with no DataSource has no pool — that is not a pool of size 0");
        }

        @Test
        @DisplayName("before/after produces a change for counters and keeps one-sided rows")
        void beforeAfterDeltas() {
            PrometheusSnapshot before = PrometheusSnapshot.builder()
                    .service("payment-executor",
                            "http_server_requests_seconds_count{uri=\"/v1/pay\",} 100.0").build();
            PrometheusSnapshot after = PrometheusSnapshot.builder()
                    .service("payment-executor",
                            "http_server_requests_seconds_count{uri=\"/v1/pay\",} 1100.0")
                    .service("transaction-mgmt", "jvm_threads_live_threads 30.0").build();

            List<PrometheusSnapshot.Delta> deltas = PrometheusSnapshot.compare(before, after);
            PrometheusSnapshot.Delta requests = deltas.stream()
                    .filter(d -> d.metric().equals("http_server_requests_seconds_count")).findFirst()
                    .orElseThrow();
            assertEquals(1000.0, requests.change(), 0.001);

            PrometheusSnapshot.Delta oneSided = deltas.stream()
                    .filter(d -> d.service().equals("transaction-mgmt")).findFirst().orElseThrow();
            assertNull(oneSided.before(), "a service that only answered the after-scrape still appears");
            assertNull(oneSided.change(), "and its change is unknown, not 0");
        }

        @Test
        @DisplayName("a failed scrape is recorded, not thrown")
        void failuresAreData() {
            PrometheusSnapshot snap = PrometheusSnapshot.builder()
                    .failed("api-gateway", "HTTP 401").build();
            assertEquals("HTTP 401", snap.failures().get("api-gateway"));
        }
    }

    @Nested
    @DisplayName("LoadOptions")
    class Cli {

        @Test
        @DisplayName("defaults point at the run-fleet.ps1 18xxx band")
        void defaults() {
            LoadOptions opts = LoadOptions.parse(new String[0]);
            assertEquals("http://localhost:18084", opts.paymentExecutorBaseUrl);
            assertEquals("http://localhost:18101", opts.rateFxBaseUrl);
            assertEquals(LoadOptions.Scenario.WALLET_PAY, opts.scenario);
            assertFalse(opts.acknowledged, "the acknowledgement must never default to true");
        }

        @Test
        @DisplayName("parses the knobs a capacity run actually turns")
        void parsesKnobs() {
            LoadOptions opts = LoadOptions.parse(new String[] {
                    "--scenario=authorize-confirm", "--rate=50", "--concurrency=64",
                    "--duration=5m", "--warmup=30s", "--timeout=1500ms",
                    LoadTargetGuard.ACK_FLAG});
            assertEquals(LoadOptions.Scenario.AUTHORIZE_CONFIRM, opts.scenario);
            assertEquals(50.0, opts.rate, 0.0001);
            assertEquals(64, opts.concurrency);
            assertEquals(Duration.ofMinutes(5), opts.duration);
            assertEquals(Duration.ofSeconds(30), opts.warmup);
            assertEquals(Duration.ofMillis(1500), opts.requestTimeout);
            assertTrue(opts.acknowledged);
        }

        @Test
        @DisplayName("trailing slashes are stripped so URL joins cannot double up")
        void stripsTrailingSlash() {
            LoadOptions opts = LoadOptions.parse(new String[] {"--payment-executor-url=http://localhost:18084/"});
            assertEquals("http://localhost:18084", opts.paymentExecutorBaseUrl);
        }

        @Test
        @DisplayName("an unknown or malformed option fails loudly rather than being ignored")
        void rejectsBadOptions() {
            assertThrows(IllegalArgumentException.class,
                    () -> LoadOptions.parse(new String[] {"--rate=0"}));
            assertThrows(IllegalArgumentException.class,
                    () -> LoadOptions.parse(new String[] {"--rate=fast"}));
            assertThrows(IllegalArgumentException.class,
                    () -> LoadOptions.parse(new String[] {"--nope=1"}));
            assertThrows(IllegalArgumentException.class,
                    () -> LoadOptions.parse(new String[] {"-rate 5"}));
            assertThrows(IllegalArgumentException.class,
                    () -> LoadOptions.parse(new String[] {"--scenario=stress-everything"}));
            assertThrows(IllegalArgumentException.class,
                    () -> LoadOptions.parse(new String[] {"--duration=soon"}));
        }

        @Test
        @DisplayName("--scrape=name=url pairs parse; an empty value disables scraping")
        void scrapeTargets() {
            LoadOptions withTargets = LoadOptions.parse(new String[] {
                    "--scrape=payment-executor=http://localhost:18084,rate-fx=http://localhost:18101/"});
            assertEquals(Map.of("payment-executor", "http://localhost:18084",
                    "rate-fx", "http://localhost:18101"), withTargets.scrapeTargets);
            assertTrue(LoadOptions.parse(new String[] {"--scrape="}).scrapeTargets.isEmpty());
            assertThrows(IllegalArgumentException.class,
                    () -> LoadOptions.parse(new String[] {"--scrape=justaname"}));
        }

        @Test
        @DisplayName("allTargets() covers every URL the guard must vet, including scrape targets")
        void allTargetsIsComplete() {
            LoadOptions opts = LoadOptions.parse(new String[] {"--scenario=authorize-confirm"});
            assertTrue(opts.allTargets().contains(opts.paymentExecutorBaseUrl));
            assertTrue(opts.allTargets().contains(opts.rateFxBaseUrl));
            assertTrue(opts.allTargets().containsAll(opts.scrapeTargets.values()));
        }
    }

    @Nested
    @DisplayName("Results accumulator")
    class Accumulator {

        @Test
        @DisplayName("errors are counted but NOT timed; declines are both")
        void errorsAreNotTimed() {
            LoadHarness.Results results = new LoadHarness.Results();
            results.record(Outcome.ok(10_000_000L, new long[0], new String[0]));
            results.record(Outcome.declined("SCHEME_CLOSED", 409, 20_000_000L));
            results.record(Outcome.error("CONNECTION_REFUSED", 0, 1_000L));

            assertEquals(3, results.attempted.get());
            assertEquals(1, results.ok.get());
            assertEquals(1, results.declined.get());
            assertEquals(1, results.errored.get());
            assertEquals(2, results.overall.count(),
                    "the sub-millisecond connection-refused must NOT enter the percentiles");
        }

        @Test
        @DisplayName("a shed arrival is counted separately, under its own code")
        void shedIsItsOwnBucket() {
            LoadHarness.Results results = new LoadHarness.Results();
            results.record(Outcome.shed());
            assertEquals(1, results.shed.get());
            assertEquals(0, results.errored.get(), "shedding is the harness's limit, not a platform error");
            assertEquals(Map.of("CONCURRENCY_CAP", 1L), results.codeTally());
        }

        @Test
        @DisplayName("per-step latencies are kept for the multi-step scenario")
        void perStepLatencies() {
            LoadHarness.Results results = new LoadHarness.Results();
            results.record(Outcome.ok(60_000_000L,
                    new long[] {10_000_000L, 20_000_000L, 30_000_000L},
                    new String[] {"quote", "authorize", "confirm"}));
            assertEquals(3, results.perStep.size());
            assertEquals(30.0, results.perStep.get("confirm").percentileMs(50), 0.001);
        }

        @Test
        @DisplayName("the code tally is ordered by count, highest first")
        void tallyOrdering() {
            LoadHarness.Results results = new LoadHarness.Results();
            results.record(Outcome.declined("A", 422, 1));
            results.record(Outcome.declined("B", 422, 1));
            results.record(Outcome.declined("B", 422, 1));
            assertEquals(List.of("B", "A"), List.copyOf(results.codeTally().keySet()));
        }
    }
}
