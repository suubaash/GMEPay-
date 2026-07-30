package com.gme.pay.payment.metrics;

import com.gme.pay.errors.ApiError;
import com.gme.pay.errors.ErrorCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the payment SLI's two load-bearing properties: it classifies outcomes the way an SLA
 * conversation needs, and it cannot affect the payment.
 */
@DisplayName("PaymentSliMetrics — payment-path SLIs (T3-5)")
class PaymentSliMetricsTest {

    private MeterRegistry registry;
    private PaymentSliMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new PaymentSliMetrics(registry);
    }

    @Nested
    @DisplayName("outcome classification")
    class Classification {

        @Test
        @DisplayName("2xx is approved")
        void successIsApproved() {
            assertEquals("approved", PaymentSliMetrics.outcomeOf(ResponseEntity.ok("x")));
            assertEquals("approved",
                    PaymentSliMetrics.outcomeOf(ResponseEntity.status(HttpStatus.CREATED).body("x")));
        }

        @Test
        @DisplayName("a structured 4xx is a DECLINE, not an error — the platform worked")
        void declineIsNotError() {
            assertEquals("declined", PaymentSliMetrics.outcomeOf(
                    ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).build()));
            assertEquals("declined", PaymentSliMetrics.outcomeOf(
                    ResponseEntity.status(HttpStatus.CONFLICT).build()));
        }

        @Test
        @DisplayName("5xx is an error")
        void serverFailureIsError() {
            assertEquals("error", PaymentSliMetrics.outcomeOf(
                    ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build()));
        }

        @Test
        @DisplayName("429 is an ERROR even though it is a 4xx — being throttled is a capacity failure")
        void rateLimitedIsError() {
            assertEquals("error", PaymentSliMetrics.outcomeOf(
                    ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build()));
        }
    }

    @Nested
    @DisplayName("reason tags stay bounded")
    class Reasons {

        @Test
        @DisplayName("an approved payment has no reason")
        void approvedHasNoReason() {
            assertEquals("none",
                    PaymentSliMetrics.reasonOf("approved", ResponseEntity.ok("x")));
        }

        @Test
        @DisplayName("the ApiError envelope's code becomes the reason")
        void apiErrorCodeIsUsed() {
            ApiError error = ApiError.of(ErrorCode.TRANSACTION_LIMIT_EXCEEDED, "over the cap");
            assertEquals("transaction_limit_exceeded", PaymentSliMetrics.reasonOf("declined",
                    ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(error)));
        }

        @Test
        @DisplayName("an unrecognised body is 'unclassified', never free text")
        void unknownBodyIsUnclassified() {
            assertEquals("unclassified", PaymentSliMetrics.reasonOf("declined",
                    ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body("a wall of prose the merchant typed")));
        }

        @Test
        @DisplayName("a long or dirty reason is normalised and truncated")
        void reasonsAreCapped() {
            String messy = "  Some Reason With Spaces / Slashes ".repeat(10);
            String tag = PaymentSliMetrics.normalise(messy);
            assertTrue(tag.length() <= PaymentSliMetrics.MAX_REASON_LENGTH,
                    "an unbounded tag value would multiply the series count without limit");
            assertTrue(tag.matches("[a-z0-9_]+"), "got: " + tag);
        }

        @Test
        @DisplayName("blank and null degrade to 'unclassified' rather than an empty tag")
        void blanksAreHandled() {
            assertEquals("unclassified", PaymentSliMetrics.normalise(null));
            assertEquals("unclassified", PaymentSliMetrics.normalise("   "));
            assertEquals("unclassified", PaymentSliMetrics.normalise("///"));
        }
    }

    @Nested
    @DisplayName("recording")
    class Recording {

        @Test
        @DisplayName("records a timer sample and an outcome counter, and returns the response")
        void recordsAndPassesThrough() {
            ResponseEntity<String> expected = ResponseEntity.status(HttpStatus.CREATED).body("ok");

            ResponseEntity<String> actual =
                    metrics.record(PaymentSliMetrics.ENTRY_WALLET_PAY, () -> expected);

            assertSame(expected, actual, "the wrapper must be invisible to the caller");
            assertEquals(1, registry.get(PaymentSliMetrics.TIMER_NAME)
                    .tag("entry", "wallet_pay").tag("outcome", "approved").timer().count());
            assertEquals(1.0, registry.get(PaymentSliMetrics.COUNTER_NAME)
                    .tag("entry", "wallet_pay").tag("outcome", "approved")
                    .tag("reason", "none").counter().count());
        }

        @Test
        @DisplayName("a thrown exception is counted as an error and RETHROWN unchanged")
        void exceptionsAreCountedAndRethrown() {
            IllegalStateException boom = new IllegalStateException("scheme exploded");

            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> metrics.record(PaymentSliMetrics.ENTRY_CONFIRM, () -> {
                        throw boom;
                    }));

            assertSame(boom, thrown, "measurement must never swallow or wrap a payment failure");
            assertEquals(1.0, registry.get(PaymentSliMetrics.COUNTER_NAME)
                    .tag("entry", "confirm").tag("outcome", "error")
                    .tag("reason", "illegalstateexception").counter().count());
        }

        @Test
        @DisplayName("a percentile histogram is published, so p95/p99 aggregate across replicas")
        void publishesHistogramBuckets() {
            // Asserted against the PROMETHEUS registry, not SimpleMeterRegistry: buckets are
            // materialised by the registry that supports them, and Prometheus is the one every
            // service actually runs (root build.gradle puts micrometer-registry-prometheus on
            // all 20 deployables). SimpleMeterRegistry reports no buckets even when the timer is
            // configured for them, so asserting there would prove nothing about production.
            io.micrometer.prometheusmetrics.PrometheusMeterRegistry prometheus =
                    new io.micrometer.prometheusmetrics.PrometheusMeterRegistry(
                            io.micrometer.prometheusmetrics.PrometheusConfig.DEFAULT);
            new PaymentSliMetrics(prometheus)
                    .record(PaymentSliMetrics.ENTRY_AUTHORIZE, () -> ResponseEntity.ok("x"));

            var snapshot = prometheus.get(PaymentSliMetrics.TIMER_NAME)
                    .tag("entry", "authorize").timer().takeSnapshot();
            assertNotNull(snapshot);
            assertTrue(snapshot.histogramCounts().length > 0,
                    "without buckets a Prometheus query cannot compute a percentile over a window");
            assertTrue(prometheus.scrape().contains("gmepay_payment_duration_seconds_bucket"),
                    "the scrape must expose _bucket series — that is what an SLO query reads");
        }

        @Test
        @DisplayName("with no registry the wrapper is a pass-through and records nothing")
        void noRegistryIsAPassThrough() {
            PaymentSliMetrics unmetered = new PaymentSliMetrics(null);
            ResponseEntity<String> expected = ResponseEntity.ok("x");

            assertSame(expected, unmetered.record(PaymentSliMetrics.ENTRY_WALLET_PAY, () -> expected));
            assertNull(registry.find(PaymentSliMetrics.TIMER_NAME).timer(),
                    "a missing registry must mean 'not measured', not a failure");
        }
    }
}
