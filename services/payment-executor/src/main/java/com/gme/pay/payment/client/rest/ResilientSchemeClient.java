package com.gme.pay.payment.client.rest;

import com.gme.pay.payment.domain.SchemeTimeoutException;
import com.gme.pay.payment.domain.client.SchemeClient;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Production-grade resilience wrapper (feat/resilience-scheme-breaker) around the OUTBOUND scheme
 * calls: a <b>per-scheme circuit breaker + bulkhead (semaphore)</b> so a dead or slow QR scheme
 * <em>fails fast</em> and triggers failover instead of hanging / hammering the adapter.
 *
 * <h2>Where it sits</h2>
 * This is the {@code @Primary} {@link SchemeClient}; it decorates {@link SchemeClientRouter} (which
 * remains the scheme-keyed dispatcher). Every {@code submitMpm / submitCpm / lookupStatus /
 * checkBalance} call is wrapped by a breaker+bulkhead <b>keyed on the scheme id</b> (breaker/bulkhead
 * instance name = the schemeId, e.g. {@code "NEPAL"}, {@code "ZEROPAY"}), so one dead scheme does NOT
 * trip the others. Instances are obtained programmatically from the {@link CircuitBreakerRegistry} /
 * {@link BulkheadRegistry} by name because the scheme id is dynamic (blanket {@code @CircuitBreaker}
 * annotations cannot key on a runtime value).
 *
 * <h2>Failover contract (CRITICAL — must not change money semantics)</h2>
 * When the breaker is OPEN it short-circuits with {@link CallNotPermittedException}; when the bulkhead
 * is saturated it rejects with {@link BulkheadFullException}. Both are <b>technical</b> failures (no
 * business decision was made, and — because an open breaker short-circuits BEFORE any HTTP call — no
 * charge occurred). We translate both to {@link SchemeTimeoutException}, which is a
 * {@code PaymentException}; the existing {@code FailoverPaymentRouter} already catches
 * {@code PaymentException} as a technical failure and fails over to the next candidate (after its
 * anti-double-charge {@code lookupStatus} guard). So an open breaker degrades to the existing
 * {@code SCHEME_UNAVAILABLE} / failover behaviour rather than bubbling up as a hard 500 — and it can
 * NEVER be mistaken for a business decline or a success.
 *
 * <p>Note: because {@code CallNotPermittedException} is raised without invoking the delegate, an open
 * breaker cannot double-submit. The {@code lookupStatus} guard call is itself wrapped; if that scheme's
 * breaker is also open the guard throws, which the router treats as "cannot confirm → NOT_FOUND →
 * safe to fail over" (no charge landed).
 *
 * <p>Read/connect timeouts are set separately on the scheme {@link org.springframework.web.client.RestClient}s
 * (see {@link RestSchemeClient} / {@link NepalRestSchemeClient}); resilience4j's {@code TimeLimiter} is
 * deliberately NOT used because these calls are synchronous, not {@code CompletableFuture}-based.
 */
@Component
@Primary
public class ResilientSchemeClient implements SchemeClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientSchemeClient.class);

    private final SchemeClient delegate;
    private final CircuitBreakerRegistry breakerRegistry;
    private final BulkheadRegistry bulkheadRegistry;

    public ResilientSchemeClient(SchemeClientRouter delegate,
                                 CircuitBreakerRegistry breakerRegistry,
                                 BulkheadRegistry bulkheadRegistry) {
        this.delegate = delegate;
        this.breakerRegistry = breakerRegistry;
        this.bulkheadRegistry = bulkheadRegistry;
    }

    @Override
    public MpmSubmitResponse submitMpm(MpmSubmitRequest request) {
        return guarded(request.schemeId(), () -> delegate.submitMpm(request));
    }

    @Override
    public CpmSubmitResponse submitCpm(CpmSubmitRequest request) {
        return guarded(request.schemeId(), () -> delegate.submitCpm(request));
    }

    @Override
    public BalanceCheckResult checkBalance(String schemeId, BigDecimal amount, String currency) {
        return guarded(schemeId, () -> delegate.checkBalance(schemeId, amount, currency));
    }

    @Override
    public LookupStatus lookupStatus(String schemeId, String reference) {
        return guarded(schemeId, () -> delegate.lookupStatus(schemeId, reference));
    }

    @Override
    public void cancelPayment(String schemeTxnRef, String reason) {
        // Legacy scheme-less cancel: the router pins it to the ZeroPay default, so guard it on the
        // ZeroPay breaker. Scheme-aware callers use cancelPayment(CancelRequest) below (T2-7).
        guarded("ZEROPAY", () -> {
            delegate.cancelPayment(schemeTxnRef, reason);
            return null;
        });
    }

    /**
     * T2-7: scheme-routed cancel/refund, guarded on the TARGET scheme's own breaker/bulkhead (a dead
     * SendMN adapter must not trip ZeroPay's breaker). A
     * {@link com.gme.pay.payment.domain.SchemeOperationNotSupportedException} from an adapter that has
     * no cancel round-trip propagates unchanged — it is a terminal contract fact, not a fault to fail
     * over on (and {@code SchemeFailureRecordPredicate} governs whether it counts against the breaker).
     */
    @Override
    public void cancelPayment(CancelRequest request) {
        guarded(request.schemeId(), () -> {
            delegate.cancelPayment(request);
            return null;
        });
    }

    /**
     * Runs {@code call} through the scheme's own circuit breaker and bulkhead. An OPEN breaker
     * ({@link CallNotPermittedException}) or a saturated bulkhead ({@link BulkheadFullException}) is
     * translated to {@link SchemeTimeoutException} — a technical failure the router fails over on.
     * All other exceptions (incl. {@code SchemeDeclinedException}, {@code SchemeTimeoutException} from
     * a real timeout) propagate unchanged so business-decline terminality is preserved.
     */
    private <T> T guarded(String schemeId, Supplier<T> call) {
        String key = instanceName(schemeId);
        CircuitBreaker breaker = breakerRegistry.circuitBreaker(key);
        Bulkhead bulkhead = bulkheadRegistry.bulkhead(key);
        // Bulkhead (semaphore) OUTSIDE the breaker so a saturated scheme also short-circuits fast;
        // the breaker still records the call outcome for the decorated supplier.
        Supplier<T> decorated = Bulkhead.decorateSupplier(bulkhead,
                CircuitBreaker.decorateSupplier(breaker, call));
        try {
            return decorated.get();
        } catch (CallNotPermittedException ex) {
            log.warn("Scheme {} circuit breaker OPEN — short-circuiting (no HTTP call) and failing over",
                    key);
            throw new SchemeTimeoutException(schemeId != null ? schemeId : key);
        } catch (BulkheadFullException ex) {
            log.warn("Scheme {} bulkhead saturated — rejecting fast and failing over", key);
            throw new SchemeTimeoutException(schemeId != null ? schemeId : key);
        }
    }

    /** Breaker/bulkhead instance name = upper-cased scheme id; null/blank → the ZeroPay default. */
    private static String instanceName(String schemeId) {
        if (schemeId == null || schemeId.isBlank()) {
            return "ZEROPAY";
        }
        return schemeId.trim().toUpperCase(Locale.ROOT);
    }
}
