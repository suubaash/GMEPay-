package com.gme.pay.gateway.filter;

import com.gme.pay.gateway.replay.InMemoryNonceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReplayProtectionFilter}. No Spring context — the filter is constructed
 * directly with a real {@link InMemoryNonceStore} and driven with mock exchanges (mirrors
 * {@link HmacSignatureFilterTest}). The shared filter instance keeps one nonce store across calls
 * so a replay is observable.
 */
class ReplayProtectionFilterTest {

    private static final String PARTNER_ID = "partner_test_001";

    private ReplayProtectionFilter filter;
    private AtomicBoolean chainCalled;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new ReplayProtectionFilter(
                new InMemoryNonceStore(), ReplayProtectionFilter.DEFAULT_NONCE_TTL_SECONDS);
        chainCalled = new AtomicBoolean(false);
        chain = exchange -> {
            chainCalled.set(true);
            return Mono.empty();
        };
    }

    private MockServerWebExchange partnerExchange(String nonce) {
        MockServerHttpRequest.BodyBuilder b = MockServerHttpRequest.post("/v1/payments");
        if (nonce != null) {
            b.header("X-Nonce", nonce);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(b.build());
        exchange.getAttributes().put(HmacSignatureFilter.ATTR_PARTNER_ID, PARTNER_ID);
        return exchange;
    }

    @Test
    @DisplayName("Non-partner request (no partner_id attr) passes straight through")
    void noPartnerId_passesThrough() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").build());

        filter.filter(exchange, chain).block();

        assertTrue(chainCalled.get(), "non-partner traffic must not be replay-checked");
        assertNull(exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Fresh nonce passes; the same nonce replayed is rejected with 401 REPLAY_DETECTED")
    void freshNoncePasses_replayRejected() {
        MockServerWebExchange first = partnerExchange("nonce-1");
        filter.filter(first, chain).block();
        assertTrue(chainCalled.get(), "fresh nonce must pass to the chain");
        assertNull(first.getResponse().getStatusCode());

        chainCalled.set(false);
        MockServerWebExchange replay = partnerExchange("nonce-1"); // identical nonce, same store
        filter.filter(replay, chain).block();
        assertFalse(chainCalled.get(), "a replay must not reach the chain");
        assertEquals(HttpStatus.UNAUTHORIZED, replay.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("A different nonce for the same partner passes")
    void differentNoncePasses() {
        filter.filter(partnerExchange("nonce-A"), chain).block();
        chainCalled.set(false);
        MockServerWebExchange other = partnerExchange("nonce-B");
        filter.filter(other, chain).block();
        assertTrue(chainCalled.get());
        assertNull(other.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Missing X-Nonce on a partner request is rejected with 400 MISSING_NONCE")
    void missingNonce_returns400() {
        MockServerWebExchange exchange = partnerExchange(null);
        filter.filter(exchange, chain).block();
        assertFalse(chainCalled.get());
        assertEquals(HttpStatus.BAD_REQUEST, exchange.getResponse().getStatusCode());
    }
}
