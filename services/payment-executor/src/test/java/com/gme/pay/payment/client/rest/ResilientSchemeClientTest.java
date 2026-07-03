package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.SchemeDeclinedException;
import com.gme.pay.payment.domain.SchemeTimeoutException;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.SchemeClient.MpmSubmitRequest;
import com.gme.pay.payment.domain.client.SchemeClient.MpmSubmitResponse;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link ResilientSchemeClient} — per-scheme circuit breaker + bulkhead on the outbound
 * scheme edge (feat/resilience-scheme-breaker). In-process only: the delegate {@link SchemeClient} is
 * a Mockito mock, no network.
 *
 * <p>Breaker config here is deliberately small (window 4, threshold 50%, min-calls 4) so it trips
 * within a handful of calls; the production defaults live in application.properties.
 */
class ResilientSchemeClientTest {

    private SchemeClientRouter delegate;
    private CircuitBreakerRegistry breakerRegistry;
    private BulkheadRegistry bulkheadRegistry;
    private ResilientSchemeClient client;

    private static MpmSubmitRequest req(String schemeId) {
        return new MpmSubmitRequest("REF-" + schemeId, null,
                new BigDecimal("1000"), "KRW", schemeId, "qr");
    }

    @BeforeEach
    void setUp() {
        delegate = mock(SchemeClientRouter.class);
        CircuitBreakerConfig cbCfg = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                // Business declines must NOT trip the breaker (mirrors the prod predicate).
                .recordException(new SchemeFailureRecordPredicate())
                .build();
        breakerRegistry = CircuitBreakerRegistry.of(cbCfg);
        bulkheadRegistry = BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(16).build());
        client = new ResilientSchemeClient(delegate, breakerRegistry, bulkheadRegistry);
    }

    @Test
    @DisplayName("breaker OPENS after configured failures, then short-circuits without calling the delegate")
    void breakerOpensAndShortCircuits() {
        when(delegate.submitMpm(any())).thenThrow(new SchemeTimeoutException("NEPAL"));

        // 4 real failures fill the window and trip the breaker to OPEN.
        for (int i = 0; i < 4; i++) {
            assertThrows(SchemeTimeoutException.class, () -> client.submitMpm(req("NEPAL")));
        }
        assertEquals(CircuitBreaker.State.OPEN,
                breakerRegistry.circuitBreaker("NEPAL").getState());

        reset(delegate); // clear invocation count; any further delegate call would be a real HTTP hit

        // Next call: short-circuited (CallNotPermittedException) → mapped to SchemeTimeoutException,
        // and the delegate is NEVER touched (no HTTP, no charge).
        assertThrows(SchemeTimeoutException.class, () -> client.submitMpm(req("NEPAL")));
        verify(delegate, never()).submitMpm(any());
    }

    @Test
    @DisplayName("open breaker maps CallNotPermittedException to the technical SchemeTimeoutException path")
    void openBreakerMapsToTechnicalFailure() {
        when(delegate.submitMpm(any())).thenThrow(new SchemeTimeoutException("NEPAL"));
        for (int i = 0; i < 4; i++) {
            assertThrows(SchemeTimeoutException.class, () -> client.submitMpm(req("NEPAL")));
        }
        // The short-circuit surfaces as SchemeTimeoutException (a PaymentException) — the exact
        // technical-failure type FailoverPaymentRouter fails over on. Never a decline, never success.
        Throwable t = assertThrows(Throwable.class, () -> client.submitMpm(req("NEPAL")));
        assertInstanceOf(SchemeTimeoutException.class, t);
    }

    @Test
    @DisplayName("breaker is PER-SCHEME: tripping NEPAL leaves ZEROPAY closed and serving")
    void breakerIsPerScheme() {
        // NEPAL fails; ZEROPAY is healthy.
        when(delegate.submitMpm(any())).thenAnswer(inv -> {
            MpmSubmitRequest r = inv.getArgument(0);
            if ("NEPAL".equals(r.schemeId())) {
                throw new SchemeTimeoutException("NEPAL");
            }
            return new MpmSubmitResponse("ZP_OK", "ZP-TXN", Instant.now());
        });

        for (int i = 0; i < 4; i++) {
            assertThrows(SchemeTimeoutException.class, () -> client.submitMpm(req("NEPAL")));
        }
        assertEquals(CircuitBreaker.State.OPEN, breakerRegistry.circuitBreaker("NEPAL").getState());
        // ZEROPAY breaker untouched → still CLOSED, still serving approvals.
        assertEquals(CircuitBreaker.State.CLOSED, breakerRegistry.circuitBreaker("ZEROPAY").getState());
        MpmSubmitResponse resp = client.submitMpm(req("ZEROPAY"));
        assertEquals("ZP-TXN", resp.schemeTxnRef());
        assertEquals(CircuitBreaker.State.CLOSED, breakerRegistry.circuitBreaker("ZEROPAY").getState());
    }

    @Test
    @DisplayName("business declines do NOT trip the breaker (stays CLOSED)")
    void businessDeclinesDoNotTripBreaker() {
        when(delegate.submitMpm(any()))
                .thenThrow(new SchemeDeclinedException("receiver_not_found", "no such receiver"));
        for (int i = 0; i < 6; i++) {
            assertThrows(SchemeDeclinedException.class, () -> client.submitMpm(req("NEPAL")));
        }
        // Authoritative declines are not availability faults → breaker must remain CLOSED.
        assertEquals(CircuitBreaker.State.CLOSED, breakerRegistry.circuitBreaker("NEPAL").getState());
    }

    @Test
    @DisplayName("healthy scheme passes results through unchanged")
    void healthyPassThrough() {
        MpmSubmitResponse expected = new MpmSubmitResponse("ZP_OK", "ZP-TXN-1", Instant.now());
        when(delegate.submitMpm(any())).thenReturn(expected);
        MpmSubmitResponse resp = client.submitMpm(req("ZEROPAY"));
        assertSame(expected, resp);
        verify(delegate, times(1)).submitMpm(any());
        verify(delegate, atMost(1)).submitMpm(any());
    }
}
