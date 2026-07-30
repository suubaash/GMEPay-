package com.gme.pay.gateway.filter;

import com.gme.pay.gateway.ratelimit.InMemoryRateLimitStore;
import com.gme.pay.gateway.ratelimit.RateLimitProperties;
import com.gme.pay.gateway.ratelimit.RateLimitStore;
import com.gme.pay.gateway.replay.InMemoryNonceStore;
import com.gme.pay.gateway.replay.NonceStore;
import com.gme.pay.gateway.replay.ReplayProtectionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>"Redis is down" is a posture, not an accident.</b>
 *
 * <p>Moving the rate-limit window and the nonce set into Redis introduces a dependency that can be
 * unavailable, and the two obvious outcomes are both bad: "no rate limiting" silently undoes T0-7,
 * and "the platform 503s" invents a single point of failure. This test pins what the shipped
 * configuration actually does in each branch, so a future change to the default is a failing test
 * rather than a discovery during an incident.
 *
 * <p>The T0-7 branches themselves (fail-open true/false) are exercised by
 * {@link RateLimitFilterTest} and must not regress; what is added here is the third option
 * ({@code LOCAL}) and the replay side, which previously had no error handling at all — a store
 * error would have escaped the reactive pipeline as a bare 500.
 */
class EdgeStoreUnavailablePostureTest {

    private static final String PARTNER_ID = "partner_test_001";
    private static final RuntimeException REDIS_DOWN =
            new IllegalStateException("Unable to connect to Redis");

    private final AtomicInteger chainCalls = new AtomicInteger();
    private final GatewayFilterChain chain = exchange -> {
        chainCalls.incrementAndGet();
        return Mono.empty();
    };

    // ================================================================ rate limit

    @Nested
    @DisplayName("rate limit")
    class RateLimit {

        @Test
        @DisplayName("DEFAULT is DENY: an unavailable store answers 429 and forwards nothing")
        void defaultIsDeny() {
            RateLimitProperties props = enabled();
            assertThat(props.effectiveOnStoreError())
                    .as("the shipped default must stay T0-7's fail-closed posture")
                    .isEqualTo(RateLimitProperties.OnStoreError.DENY);

            MockServerWebExchange exchange = run(props, null);

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(chainCalls.get()).as("nothing reaches downstream").isZero();
        }

        @Test
        @DisplayName("LOCAL degrades to the per-JVM window: still a cap, at N x the value")
        void localDegradesRatherThanFailing() {
            RateLimitProperties props = enabled();
            props.setOnStoreError(RateLimitProperties.OnStoreError.LOCAL);
            props.setPaymentsPerSecond(2);
            InMemoryRateLimitStore fallback = new InMemoryRateLimitStore();

            // Three requests, one broken Redis, a local cap of 2.
            RateLimitFilter filter = new RateLimitFilter(brokenStore(), props, fallback);
            assertThat(status(filter)).isNull();       // 1st — allowed by the fallback
            assertThat(status(filter)).isNull();       // 2nd — allowed
            assertThat(status(filter))
                    .as("the fallback is a real counter, not a rubber stamp: the 3rd is over the "
                            + "per-JVM cap and is still refused")
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(chainCalls.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("LOCAL with no fallback wired degrades to DENY, never to ALLOW")
        void localWithoutFallbackDenies() {
            RateLimitProperties props = enabled();
            props.setOnStoreError(RateLimitProperties.OnStoreError.LOCAL);

            MockServerWebExchange exchange = run(props, null);

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(chainCalls.get()).isZero();
        }

        @Test
        @DisplayName("the legacy fail-open:true still means ALLOW and still outranks on-store-error")
        void legacyFailOpenStillMeansAllow() {
            RateLimitProperties props = enabled();
            props.setFailOpen(true);
            props.setOnStoreError(RateLimitProperties.OnStoreError.DENY);

            assertThat(props.effectiveOnStoreError())
                    .as("a deployment that set fail-open:true keeps the behaviour it configured; "
                            + "silently re-interpreting it would be its own posture change")
                    .isEqualTo(RateLimitProperties.OnStoreError.ALLOW);

            run(props, new InMemoryRateLimitStore());
            assertThat(chainCalls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("ALLOW admits traffic — named so nobody reaches it by accident")
        void allowAdmits() {
            RateLimitProperties props = enabled();
            props.setOnStoreError(RateLimitProperties.OnStoreError.ALLOW);

            MockServerWebExchange exchange = run(props, null);

            assertThat(exchange.getResponse().getStatusCode()).isNull();
            assertThat(chainCalls.get()).isEqualTo(1);
        }

        private MockServerWebExchange run(RateLimitProperties props, InMemoryRateLimitStore fb) {
            RateLimitFilter filter = new RateLimitFilter(brokenStore(), props, fb);
            MockServerWebExchange exchange = partnerPost();
            filter.filter(exchange, chain).block();
            return exchange;
        }

        private HttpStatus status(RateLimitFilter filter) {
            MockServerWebExchange exchange = partnerPost();
            filter.filter(exchange, chain).block();
            return (HttpStatus) exchange.getResponse().getStatusCode();
        }

        private RateLimitProperties enabled() {
            RateLimitProperties p = new RateLimitProperties();
            p.setEnabled(true);
            return p;
        }

        private RateLimitStore brokenStore() {
            return (key, limit, window) -> Mono.error(REDIS_DOWN);
        }

        private MockServerWebExchange partnerPost() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/v1/payments").build());
            exchange.getAttributes().put(HmacSignatureFilter.ATTR_PARTNER_ID, PARTNER_ID);
            return exchange;
        }
    }

    // ================================================================ replay

    @Nested
    @DisplayName("replay protection")
    class Replay {

        @Test
        @DisplayName("there is no ALLOW posture at all — the enum has exactly two values")
        void noAllowPostureExists() {
            assertThat(ReplayProtectionProperties.OnStoreError.values())
                    .as("a replay check an attacker can switch off by making one Redis "
                            + "unreachable is not a replay check")
                    .containsExactly(ReplayProtectionProperties.OnStoreError.REJECT,
                            ReplayProtectionProperties.OnStoreError.LOCAL);
        }

        @Test
        @DisplayName("DEFAULT is REJECT: 503 REPLAY_STORE_UNAVAILABLE, request not forwarded")
        void defaultIsReject() {
            ReplayProtectionProperties props = new ReplayProtectionProperties();
            assertThat(props.getOnStoreError())
                    .isEqualTo(ReplayProtectionProperties.OnStoreError.REJECT);

            MockServerWebExchange exchange = run(props, new InMemoryNonceStore());

            assertThat(exchange.getResponse().getStatusCode())
                    .as("503, not the bare 500 an unhandled reactive error used to produce")
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(chainCalls.get()).isZero();
        }

        @Test
        @DisplayName("LOCAL degrades to the per-JVM nonce set and still catches a same-pod replay")
        void localStillCatchesSamePodReplay() {
            ReplayProtectionProperties props = new ReplayProtectionProperties();
            props.setOnStoreError(ReplayProtectionProperties.OnStoreError.LOCAL);
            ReplayProtectionFilter filter = new ReplayProtectionFilter(
                    brokenStore(), new InMemoryNonceStore(), props);

            MockServerWebExchange first = partnerExchange("nonce-1");
            filter.filter(first, chain).block();
            assertThat(first.getResponse().getStatusCode())
                    .as("degraded, but the request is served").isNull();

            MockServerWebExchange replay = partnerExchange("nonce-1");
            filter.filter(replay, chain).block();
            assertThat(replay.getResponse().getStatusCode())
                    .as("the fallback is a real nonce set — the check is weaker (one replay per "
                            + "replica), not absent")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(chainCalls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("LOCAL with no fallback wired degrades to REJECT, never to pass-through")
        void localWithoutFallbackRejects() {
            ReplayProtectionProperties props = new ReplayProtectionProperties();
            props.setOnStoreError(ReplayProtectionProperties.OnStoreError.LOCAL);
            ReplayProtectionFilter filter = new ReplayProtectionFilter(brokenStore(), 300L);

            MockServerWebExchange exchange = partnerExchange("nonce-1");
            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(chainCalls.get()).isZero();
        }

        @Test
        @DisplayName("an empty store reply is NOT treated as fresh")
        void emptyReplyIsNotFresh() {
            NonceStore silent = (p, n, ttl) -> Mono.empty();
            ReplayProtectionFilter filter = new ReplayProtectionFilter(silent, 300L);

            MockServerWebExchange exchange = partnerExchange("nonce-1");
            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode())
                    .as("'I do not know whether this nonce is new' must not resolve to 'accept'")
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(chainCalls.get()).isZero();
        }

        @Test
        @DisplayName("an over-long X-Nonce is 400 before it is written to shared infrastructure")
        void overLongNonceRejected() {
            ReplayProtectionProperties props = new ReplayProtectionProperties();
            props.setMaxNonceLength(16);
            AtomicInteger storeCalls = new AtomicInteger();
            NonceStore counting = (p, n, ttl) -> {
                storeCalls.incrementAndGet();
                return Mono.just(true);
            };
            ReplayProtectionFilter filter =
                    new ReplayProtectionFilter(counting, new InMemoryNonceStore(), props);

            MockServerWebExchange exchange = partnerExchange("x".repeat(17));
            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(storeCalls.get())
                    .as("a partner-sized write into a store shared with every other tenant is "
                            + "refused at the edge, not at the Redis client")
                    .isZero();
            assertThat(chainCalls.get()).isZero();
        }

        @Test
        @DisplayName("the 256-char default is generous enough for any sane nonce")
        void defaultLengthAcceptsAUuid() {
            ReplayProtectionProperties props = new ReplayProtectionProperties();
            assertThat(props.getMaxNonceLength()).isGreaterThanOrEqualTo(64);

            ReplayProtectionFilter filter = new ReplayProtectionFilter(
                    (p, n, ttl) -> Mono.just(true), new InMemoryNonceStore(), props);
            MockServerWebExchange exchange =
                    partnerExchange(java.util.UUID.randomUUID().toString());
            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isNull();
            assertThat(chainCalls.get()).isEqualTo(1);
        }

        private MockServerWebExchange run(ReplayProtectionProperties props, InMemoryNonceStore fb) {
            ReplayProtectionFilter filter = new ReplayProtectionFilter(brokenStore(), fb, props);
            MockServerWebExchange exchange = partnerExchange("nonce-1");
            filter.filter(exchange, chain).block();
            return exchange;
        }

        private NonceStore brokenStore() {
            return (partnerId, nonce, ttl) -> Mono.error(REDIS_DOWN);
        }

        private MockServerWebExchange partnerExchange(String nonce) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/v1/payments").header("X-Nonce", nonce).build());
            exchange.getAttributes().put(HmacSignatureFilter.ATTR_PARTNER_ID, PARTNER_ID);
            return exchange;
        }
    }
}
