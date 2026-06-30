package com.gme.pay.gateway.filter;

import com.gme.pay.gateway.ratelimit.InMemoryRateLimitStore;
import com.gme.pay.gateway.ratelimit.RateLimitProperties;
import com.gme.pay.gateway.ratelimit.RateLimitStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RateLimitFilter}. No Spring context — the filter is built directly with a
 * real {@link InMemoryRateLimitStore} and driven with mock exchanges (mirrors
 * {@link ReplayProtectionFilterTest}).
 */
class RateLimitFilterTest {

    private static final String PARTNER_ID = "partner_test_001";

    private AtomicInteger chainCalls;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        chainCalls = new AtomicInteger(0);
        chain = exchange -> {
            chainCalls.incrementAndGet();
            return Mono.empty();
        };
    }

    private RateLimitFilter filter(RateLimitProperties props) {
        return new RateLimitFilter(new InMemoryRateLimitStore(), props);
    }

    private RateLimitProperties enabledProps() {
        RateLimitProperties p = new RateLimitProperties();
        p.setEnabled(true);
        p.setRatesPerSecond(2);
        p.setPaymentsPerSecond(50);
        p.setGlobalPerSecond(100);
        return p;
    }

    private MockServerWebExchange partnerPost(String path) {
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.post(path).build());
        exchange.getAttributes().put(HmacSignatureFilter.ATTR_PARTNER_ID, PARTNER_ID);
        return exchange;
    }

    @Test
    @DisplayName("Disabled filter passes every request through with no headers")
    void disabled_passesThrough() {
        RateLimitProperties props = new RateLimitProperties(); // enabled=false by default
        RateLimitFilter f = filter(props);

        MockServerWebExchange ex = partnerPost("/v1/rates");
        f.filter(ex, chain).block();

        assertEquals(1, chainCalls.get());
        assertNull(ex.getResponse().getHeaders().getFirst("X-RateLimit-Limit"));
    }

    @Test
    @DisplayName("Non-partner request (no partner_id attr) is never throttled")
    void noPartnerId_passesThrough() {
        RateLimitFilter f = filter(enabledProps());
        MockServerWebExchange ex =
                MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health").build());

        f.filter(ex, chain).block();

        assertEquals(1, chainCalls.get());
        assertNull(ex.getResponse().getHeaders().getFirst("X-RateLimit-Limit"));
    }

    @Test
    @DisplayName("Within-limit requests pass and carry X-RateLimit-* headers")
    void withinLimit_passesWithHeaders() {
        RateLimitFilter f = filter(enabledProps());
        MockServerWebExchange ex = partnerPost("/v1/rates");

        f.filter(ex, chain).block();

        assertEquals(1, chainCalls.get());
        assertEquals("2", ex.getResponse().getHeaders().getFirst("X-RateLimit-Limit"));
        assertEquals("1", ex.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"));
        assertNotNull(ex.getResponse().getHeaders().getFirst("X-RateLimit-Reset"));
    }

    @Test
    @DisplayName("Exceeding the per-scope limit returns 429 RATE_LIMITED with Retry-After")
    void overLimit_returns429WithRetryAfter() {
        RateLimitFilter f = filter(enabledProps()); // rates limit = 2/s, shared store across calls

        // Two allowed hits exhaust the rates window for this partner.
        f.filter(partnerPost("/v1/rates"), chain).block();
        f.filter(partnerPost("/v1/rates"), chain).block();
        assertEquals(2, chainCalls.get());

        MockServerWebExchange third = partnerPost("/v1/rates");
        f.filter(third, chain).block();

        assertEquals(2, chainCalls.get(), "the 3rd request must not reach the chain");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, third.getResponse().getStatusCode());
        assertNotNull(third.getResponse().getHeaders().getFirst("Retry-After"));
        assertEquals("0", third.getResponse().getHeaders().getFirst("X-RateLimit-Remaining"));
    }

    @Test
    @DisplayName("rates and payments scopes have independent counters")
    void scopesAreIndependent() {
        RateLimitFilter f = filter(enabledProps()); // rates=2, payments=50

        f.filter(partnerPost("/v1/rates"), chain).block();
        f.filter(partnerPost("/v1/rates"), chain).block();
        // rates now exhausted; a payments request must still pass (different scope).
        MockServerWebExchange pay = partnerPost("/v1/payments");
        f.filter(pay, chain).block();

        assertEquals(3, chainCalls.get());
        assertNull(pay.getResponse().getStatusCode(), "payments scope unaffected by rates breach");
        assertEquals("50", pay.getResponse().getHeaders().getFirst("X-RateLimit-Limit"));
    }

    @Test
    @DisplayName("fail-open: a store error lets the request through")
    void storeError_failOpen_allows() {
        RateLimitProperties props = enabledProps();
        props.setFailOpen(true);
        RateLimitStore boom = (key, limit, window) -> Mono.error(new RuntimeException("redis down"));
        RateLimitFilter f = new RateLimitFilter(boom, props);

        MockServerWebExchange ex = partnerPost("/v1/rates");
        f.filter(ex, chain).block();

        assertEquals(1, chainCalls.get(), "fail-open must let the request proceed");
        assertNull(ex.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("fail-closed: a store error rejects the request with 429")
    void storeError_failClosed_rejects() {
        RateLimitProperties props = enabledProps();
        props.setFailOpen(false);
        RateLimitStore boom = (key, limit, window) -> Mono.error(new RuntimeException("redis down"));
        RateLimitFilter f = new RateLimitFilter(boom, props);

        MockServerWebExchange ex = partnerPost("/v1/rates");
        f.filter(ex, chain).block();

        assertEquals(0, chainCalls.get());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Decision.resetAfterSeconds rounds milliseconds up to the next whole second")
    void decisionRoundsResetUp() {
        assertEquals(1, new RateLimitStore.Decision(false, 1, 0, 1).resetAfterSeconds());
        assertEquals(1, new RateLimitStore.Decision(false, 1, 0, 1000).resetAfterSeconds());
        assertEquals(2, new RateLimitStore.Decision(false, 1, 0, 1001).resetAfterSeconds());
        assertEquals(0, new RateLimitStore.Decision(true, 1, 1, 0).resetAfterSeconds());
        // sanity: Duration of a second is the window the filter uses
        assertTrue(Duration.ofSeconds(1).toMillis() == 1000);
    }
}
