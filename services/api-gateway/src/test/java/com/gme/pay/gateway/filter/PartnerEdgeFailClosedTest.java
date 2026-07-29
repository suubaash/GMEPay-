package com.gme.pay.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.gateway.partner.PartnerCredentialService;
import com.gme.pay.gateway.partner.PartnerCredentialSourceUnavailableException;
import com.gme.pay.gateway.partner.PartnerCredentials;
import com.gme.pay.gateway.partner.TestPartnerCredentials;
import com.gme.pay.gateway.ratelimit.RateLimitProperties;
import com.gme.pay.gateway.ratelimit.RateLimitStore;
import com.gme.pay.gateway.registry.IpAllowlistCache;
import com.gme.pay.gateway.registry.StubConfigRegistryClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * T0-7 — <b>every fail-open branch at the partner edge now denies.</b>
 *
 * <p>One test per branch the CISO audit named, asserted at the filter boundary (the layer that
 * actually answers the partner) rather than only at the credential-source unit level:
 *
 * <ul>
 *   <li>credential store unreachable → <b>503</b> at each of the three filters that resolve a
 *       credential ({@link PartnerIpAllowlistFilter} order 2, {@link MtlsFingerprintFilter} order 3,
 *       {@link HmacSignatureFilter} order 4) — never a pass-through to the next filter, never a
 *       bare 500;</li>
 *   <li>a bogus key → <b>401</b>, a real issued key → passes;</li>
 *   <li>{@code allowlist.trust_header_only_in_dev} and {@code allowlist.fail-open} default to
 *       <b>false</b>, and an empty allowlist denies;</li>
 *   <li>rate limiting ships <b>enabled</b> and <b>fail-closed</b>;</li>
 *   <li>the fallback config-registry client seeds <b>no</b> allowlist row for anyone.</li>
 * </ul>
 */
class PartnerEdgeFailClosedTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final String TS = "2026-06-15T12:00:00.000Z";
    private static final Clock CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private static final String REAL_KEY = "pk_live_realkeyfromissuance";
    private static final String REAL_SECRET = "sk_live_onetimeplaintext";
    private static final PartnerCredentials REAL_CREDS = new PartnerCredentials(
            "GMEREMIT", REAL_KEY, REAL_SECRET, List.of(),
            PartnerCredentials.PartnerType.OVERSEAS, 300, null);

    /** Credential source that only ever reports the store as unreachable. */
    private static final PartnerCredentialService UNAVAILABLE = apiKey ->
            Mono.error(new PartnerCredentialSourceUnavailableException("store down"));

    /** Credential source holding exactly one real, issued key. */
    private static final PartnerCredentialService REAL_SOURCE = apiKey ->
            REAL_KEY.equals(apiKey) ? Mono.just(REAL_CREDS) : Mono.empty();

    private static MockServerWebExchange signedRequest(String apiKey, String secret) {
        byte[] body = "{\"send_currency\":\"USD\"}".getBytes(StandardCharsets.UTF_8);
        String canonical = HmacSignatureVerifier.buildCanonicalString(
                "POST", "/v1/rates", TS, body);
        String sig = HmacSignatureVerifier.computeHmac(secret, canonical);
        return MockServerWebExchange.from(MockServerHttpRequest
                .post("/v1/rates")
                .header("X-API-Key", apiKey)
                .header("X-Timestamp", TS)
                .header("X-Signature", sig)
                .body(new String(body, StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------- HMAC filter: the 3 outcomes

    @Test
    @DisplayName("HMAC filter: a real issued key with a valid signature passes")
    void realKeyPasses() {
        AtomicInteger downstream = new AtomicInteger();
        GatewayFilterChain chain = exchange -> {
            downstream.incrementAndGet();
            return Mono.empty();
        };
        MockServerWebExchange exchange = signedRequest(REAL_KEY, REAL_SECRET);

        new HmacSignatureFilter(REAL_SOURCE, CLOCK, 300L).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
        assertThat(downstream.get()).isEqualTo(1);
        assertThat(exchange.getAttributes().get(HmacSignatureFilter.ATTR_PARTNER_ID))
                .isEqualTo("GMEREMIT");
    }

    @Test
    @DisplayName("HMAC filter: a bogus key → 401, downstream never invoked")
    void bogusKeyIs401() {
        AtomicInteger downstream = new AtomicInteger();
        GatewayFilterChain chain = exchange -> {
            downstream.incrementAndGet();
            return Mono.empty();
        };
        MockServerWebExchange exchange = signedRequest("pk_live_neverissued", REAL_SECRET);

        new HmacSignatureFilter(REAL_SOURCE, CLOCK, 300L).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(downstream.get()).isZero();
    }

    @Test
    @DisplayName("HMAC filter: the published stub keys are just bogus keys now → 401")
    void publishedStubKeyIs401() {
        MockServerWebExchange exchange = signedRequest(
                TestPartnerCredentials.PUBLISHED_API_KEY, TestPartnerCredentials.PUBLISHED_SECRET);

        new HmacSignatureFilter(REAL_SOURCE, CLOCK, 300L)
                .filter(exchange, e -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("HMAC filter: credential store unreachable → 503, NOT a pass and NOT a 401")
    void unavailableStoreIs503AtHmacFilter() {
        AtomicInteger downstream = new AtomicInteger();
        GatewayFilterChain chain = exchange -> {
            downstream.incrementAndGet();
            return Mono.empty();
        };
        MockServerWebExchange exchange = signedRequest(REAL_KEY, REAL_SECRET);

        new HmacSignatureFilter(UNAVAILABLE, CLOCK, 300L).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(downstream.get())
                .as("an unverifiable request must never reach a downstream service")
                .isZero();
    }

    @Test
    @DisplayName("HMAC filter: an unexpected credential-path error also denies (503), never allows")
    void unexpectedErrorFailsClosed() {
        PartnerCredentialService broken = apiKey ->
                Mono.error(new IllegalStateException("NPE-shaped bug in the credential path"));
        MockServerWebExchange exchange = signedRequest(REAL_KEY, REAL_SECRET);

        new HmacSignatureFilter(broken, CLOCK, 300L).filter(exchange, e -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ------------------------------------------------------------- mTLS filter

    @Test
    @DisplayName("mTLS filter: credential store unreachable → 503, not a pass-through to HMAC")
    void unavailableStoreIs503AtMtlsFilter() {
        AtomicInteger downstream = new AtomicInteger();
        GatewayFilterChain chain = exchange -> {
            downstream.incrementAndGet();
            return Mono.empty();
        };
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/v1/rates")
                .header("X-API-Key", REAL_KEY)
                .header(MtlsFingerprintFilter.DEFAULT_CERT_HEADER,
                        TestPartnerCredentials.MTLS_FINGERPRINT)
                .body(""));

        new MtlsFingerprintFilter(UNAVAILABLE, true, MtlsFingerprintFilter.DEFAULT_CERT_HEADER)
                .filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(downstream.get()).isZero();
    }

    // -------------------------------------------------------- IP allowlist filter

    @Test
    @DisplayName("allowlist filter: credential store unreachable → 503 at the FIRST filter in the chain")
    void unavailableStoreIs503AtAllowlistFilter() {
        AtomicInteger downstream = new AtomicInteger();
        GatewayFilterChain chain = exchange -> {
            downstream.incrementAndGet();
            return Mono.empty();
        };
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/v1/rates")
                .header(PartnerIpAllowlistFilter.HEADER_API_KEY, REAL_KEY)
                .remoteAddress(new java.net.InetSocketAddress("203.0.113.7", 443))
                .body(""));

        allowlistFilter(UNAVAILABLE, false, false).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(downstream.get()).isZero();
    }

    @Test
    @DisplayName("allowlist filter: an empty allowlist denies (403) — the fallback registry seeds none")
    void emptyAllowlistDenies() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/v1/rates")
                .header(PartnerIpAllowlistFilter.HEADER_API_KEY, REAL_KEY)
                .remoteAddress(new java.net.InetSocketAddress("203.0.113.7", 443))
                .body(""));

        allowlistFilter(REAL_SOURCE, false, false).filter(exchange, e -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("allowlist filter: a spoofed X-Partner-Id can no longer select another partner's "
            + "allowlist (header trust is off by default)")
    void spoofedPartnerHeaderIsRejected() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/v1/rates")
                .header(PartnerIpAllowlistFilter.HEADER_API_KEY, REAL_KEY)
                .header(PartnerIpAllowlistFilter.HEADER_PARTNER_ID, "SOME_OTHER_PARTNER")
                .remoteAddress(new java.net.InetSocketAddress("203.0.113.7", 443))
                .body(""));

        allowlistFilter(REAL_SOURCE, false, false).filter(exchange, e -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("the fallback config-registry client returns NO allowlist row for any partner")
    void fallbackRegistrySeedsNothing() {
        StubConfigRegistryClient fallback = new StubConfigRegistryClient();
        for (String partner : List.of("partner_test_001", "partner_test_002", "GMEREMIT")) {
            for (String env : List.of("SANDBOX", "PRODUCTION")) {
                assertThat(fallback.getIpAllowlist(partner, env).block())
                        .as("%s/%s must not come with a checked-in allowlist", partner, env)
                        .isEmpty();
            }
        }
    }

    private static PartnerIpAllowlistFilter allowlistFilter(
            PartnerCredentialService credentials, boolean trustHeader, boolean failOpen) {
        return new PartnerIpAllowlistFilter(
                new IpAllowlistCache(new StubConfigRegistryClient(), 60L),
                credentials,
                new org.springframework.beans.factory.support.DefaultListableBeanFactory()
                        .getBeanProvider(com.gme.pay.audit.AuditPublisher.class),
                trustHeader,
                "sandbox",
                failOpen);
    }

    // ------------------------------------------------------------- rate limiting

    @Test
    @DisplayName("rate limiting ships ENABLED and FAIL-CLOSED (both defaults were the unsafe ones)")
    void rateLimitDefaultsAreSafe() {
        RateLimitProperties props = new RateLimitProperties();
        assertThat(props.isEnabled()).as("the documented per-partner cap must be applied").isTrue();
        assertThat(props.isFailOpen()).as("a store error must not admit unlimited traffic").isFalse();
    }

    @Test
    @DisplayName("rate limiting: a store error now DENIES with 429 instead of admitting the request")
    void rateLimitStoreErrorDenies() {
        RateLimitStore erroring = (key, limit, window) -> Mono.error(new IllegalStateException("down"));
        AtomicInteger downstream = new AtomicInteger();
        GatewayFilterChain chain = exchange -> {
            downstream.incrementAndGet();
            return Mono.empty();
        };
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/v1/rates").body(""));
        exchange.getAttributes().put(HmacSignatureFilter.ATTR_PARTNER_ID, "GMEREMIT");

        new RateLimitFilter(erroring, new RateLimitProperties()).filter(exchange, chain).block();

        assertThat(exchange.getResponse().getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(downstream.get()).isZero();
    }

    @Test
    @DisplayName("replay protection is unconditional: a signed request without X-Nonce is rejected")
    void replayProtectionIsUnconditional() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                .post("/v1/rates").body(""));
        exchange.getAttributes().put(HmacSignatureFilter.ATTR_PARTNER_ID, "GMEREMIT");

        new ReplayProtectionFilter(
                new com.gme.pay.gateway.replay.InMemoryNonceStore(),
                ReplayProtectionFilter.DEFAULT_NONCE_TTL_SECONDS)
                .filter(exchange, e -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("replay protection: the same nonce twice inside the window is refused")
    void replayProtectionRefusesReuse() {
        com.gme.pay.gateway.replay.InMemoryNonceStore store =
                new com.gme.pay.gateway.replay.InMemoryNonceStore();
        ReplayProtectionFilter filter = new ReplayProtectionFilter(store, 300L);

        for (HttpStatus expected : List.of(HttpStatus.OK, HttpStatus.UNAUTHORIZED)) {
            MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest
                    .post("/v1/rates").header("X-Nonce", "nonce-1").body(""));
            exchange.getAttributes().put(HmacSignatureFilter.ATTR_PARTNER_ID, "GMEREMIT");
            filter.filter(exchange, e -> Mono.empty()).block();
            if (expected == HttpStatus.OK) {
                assertThat(exchange.getResponse().getStatusCode()).isNull();
            } else {
                assertThat(exchange.getResponse().getStatusCode()).isEqualTo(expected);
            }
        }
        assertThat(store.checkAndSet("GMEREMIT", "nonce-1", Duration.ofSeconds(300)).block())
                .isFalse();
    }
}
