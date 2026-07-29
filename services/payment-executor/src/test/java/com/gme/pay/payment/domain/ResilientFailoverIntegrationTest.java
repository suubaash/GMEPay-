package com.gme.pay.payment.domain;

import com.gme.pay.payment.client.rest.ResilientSchemeClient;
import com.gme.pay.payment.client.rest.SchemeClientRouter;
import com.gme.pay.payment.client.rest.SchemeFailureRecordPredicate;
import com.gme.pay.payment.domain.GmeremitPaymentService.WalletResult;
import com.gme.pay.payment.domain.client.SchemeClient;
import com.gme.pay.payment.domain.client.SchemeClient.LookupStatus;
import com.gme.pay.payment.domain.client.SchemeClient.MpmSubmitRequest;
import com.gme.pay.payment.domain.client.SchemeClient.MpmSubmitResponse;
import com.gme.pay.payment.domain.client.SmartRouterClient;
import com.gme.pay.payment.domain.client.SmartRouterClient.PartnerSchemeView;
import com.gme.pay.payment.persistence.ExecutionAttemptRepository;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end (in-process) proof that an OPEN circuit breaker on the primary scheme degrades to the
 * existing failover behaviour rather than a hard error (feat/resilience-scheme-breaker).
 *
 * <p>Composes the real {@link FailoverPaymentRouter} over a real {@link ResilientSchemeClient}
 * (breaker+bulkhead) wrapping a Mockito {@link SchemeClientRouter}. No network. This asserts the
 * critical failover contract: an open breaker → CallNotPermittedException → technical
 * SchemeTimeoutException → the router fails over to the secondary and can still approve, never
 * approving off an open breaker and never double-submitting.
 */
class ResilientFailoverIntegrationTest {

    private static final String QR = "00020101021126150011fonepay.com5802NP5910KINAUN PVT6304ABCD";
    private static final BigDecimal AMT = new BigDecimal("1000");

    /**
     * The primary (breaker-tripped) scheme. It was NEPAL until T4-1 gave the Nepal corridor its own
     * money path — a Nepal candidate is now delegated to {@code NepalPaymentService} instead of being
     * walked by the failover loop, so it can no longer stand in for "a cross-border scheme" here. The
     * breaker/failover contract under test is scheme-agnostic.
     */
    private final PartnerSchemeView primaryNepal = new PartnerSchemeView(1L, "PrimaryPartner", "khqr", 0);
    private final PartnerSchemeView secondaryZeropay = new PartnerSchemeView(2L, "SecondaryPartner", "zeropay", 1);

    private SchemeClientRouter routerDelegate;
    private CircuitBreakerRegistry breakerRegistry;
    private ResilientSchemeClient resilientClient;
    private SmartRouterClient smartRouter;
    private FailoverPaymentRouter failover;

    @BeforeEach
    void setUp() {
        routerDelegate = mock(SchemeClientRouter.class);
        CircuitBreakerConfig cbCfg = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .recordException(new SchemeFailureRecordPredicate())
                .build();
        breakerRegistry = CircuitBreakerRegistry.of(cbCfg);
        BulkheadRegistry bulkheadRegistry =
                BulkheadRegistry.of(BulkheadConfig.custom().maxConcurrentCalls(16).build());
        resilientClient = new ResilientSchemeClient(routerDelegate, breakerRegistry, bulkheadRegistry);
        smartRouter = mock(SmartRouterClient.class);
        ExecutionAttemptRepository attemptRepo = mock(ExecutionAttemptRepository.class);
        failover = new FailoverPaymentRouter(smartRouter, resilientClient, attemptRepo);
    }

    private void tripNepalBreaker() {
        when(routerDelegate.submitMpm(any(MpmSubmitRequest.class)))
                .thenThrow(new SchemeTimeoutException("khqr"));
        for (int i = 0; i < 4; i++) {
            try {
                resilientClient.submitMpm(new MpmSubmitRequest(
                        "warm-" + i, null, AMT, "NPR", "khqr", QR));
            } catch (RuntimeException ignored) {
                // expected technical failures that trip the breaker
            }
        }
        // ResilientSchemeClient keys breakers by the UPPER-CASED scheme code.
        assertEquals(CircuitBreaker.State.OPEN, breakerRegistry.circuitBreaker("KHQR").getState());
    }

    @Test
    @DisplayName("OPEN breaker on primary (primary) → router fails over to secondary (ZeroPay) and APPROVES")
    void openBreakerOnPrimary_failsOverToSecondary() {
        tripNepalBreaker();
        // Clear the warm-up invocation counts: from here primary submit must be short-circuited (never
        // delegated), while ZeroPay is served normally.
        org.mockito.Mockito.reset(routerDelegate);

        when(smartRouter.resolve(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of(primaryNepal, secondaryZeropay));
        // primary breaker is OPEN → submit short-circuits (no delegate call). ZeroPay is healthy.
        when(routerDelegate.submitMpm(argMatchesScheme("zeropay")))
                .thenReturn(new MpmSubmitResponse("ZP_OK", "ZP-TXN-2", Instant.now()));
        // The anti-double-charge guard for the OPEN primary breaker is itself short-circuited (throws) →
        // router treats it as "cannot confirm → safe to fail over" (no charge landed).
        when(routerDelegate.lookupStatus(eq("khqr"), anyString()))
                .thenReturn(LookupStatus.NOT_FOUND); // (not reached — primary breaker open)

        WalletResult result = failover.pay(QR, AMT, "user-1", "OVERSEAS");

        assertTrue(result.approved(), "should approve via the healthy secondary");
        assertEquals("ZP-TXN-2", result.schemeTxnRef());
        // primary submit was NEVER delegated post-open (short-circuited); only ZeroPay was actually submitted.
        verify(routerDelegate, never()).submitMpm(argMatchesScheme("khqr"));
        verify(routerDelegate, times(1)).submitMpm(argMatchesScheme("zeropay"));
    }

    @Test
    @DisplayName("OPEN breaker with NO healthy secondary → declined SCHEME_UNAVAILABLE (never approved, no submit)")
    void openBreakerNoSecondary_schemeUnavailable_neverApproved() {
        tripNepalBreaker();
        org.mockito.Mockito.reset(routerDelegate); // clear warm-up counts

        when(smartRouter.resolve(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of(primaryNepal)); // only the dead scheme

        WalletResult result = failover.pay(QR, AMT, "user-1", "OVERSEAS");

        assertFalse(result.approved(), "an open breaker must NEVER yield an approval");
        // primary submit was short-circuited by the open breaker → the delegate was never invoked.
        verify(routerDelegate, never()).submitMpm(argMatchesScheme("khqr"));
    }

    @Test
    @DisplayName("ZEROPAY breaker stays CLOSED while the primary is open (per-scheme isolation through the router)")
    void secondarySchemeUnaffectedByPrimaryOpen() {
        tripNepalBreaker();
        assertEquals(CircuitBreaker.State.CLOSED,
                breakerRegistry.circuitBreaker("ZEROPAY").getState());

        when(smartRouter.resolve(anyString(), any(), anyString(), anyString()))
                .thenReturn(List.of(secondaryZeropay));
        when(routerDelegate.submitMpm(argMatchesScheme("zeropay")))
                .thenReturn(new MpmSubmitResponse("ZP_OK", "ZP-TXN-9", Instant.now()));

        WalletResult result = failover.pay(QR, AMT, "user-9", "DOMESTIC");

        assertTrue(result.approved());
        verify(routerDelegate, atLeastOnce()).submitMpm(argMatchesScheme("zeropay"));
    }

    /** Mockito matcher: an MpmSubmitRequest whose schemeId equals (case-insensitively) the given code. */
    private static MpmSubmitRequest argMatchesScheme(String schemeId) {
        return org.mockito.ArgumentMatchers.argThat(r ->
                r != null && r.schemeId() != null && r.schemeId().equalsIgnoreCase(schemeId));
    }
}
