package com.gme.pay.gateway.filter;

import com.gme.pay.gateway.partner.PartnerCredentials;
import com.gme.pay.gateway.partner.PartnerCredentialService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HmacSignatureFilter}.
 *
 * <p>All tests use a pinned clock (2026-06-15T12:00:00Z) and the stub partner credentials.
 * No Spring context is started — we construct the filter directly and drive it with
 * {@link MockServerHttpRequest} / {@link MockServerWebExchange}.
 */
class HmacSignatureFilterTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final String VALID_TIMESTAMP = "2026-06-15T12:00:00.000Z";
    private static final String API_KEY = "pk_test_abc";
    private static final String SECRET  = "sk_test_xyz";
    private static final String PARTNER_ID = "partner_test_001";

    private static final PartnerCredentials CREDS = new PartnerCredentials(
            PARTNER_ID, API_KEY, SECRET, List.of(),
            PartnerCredentials.PartnerType.OVERSEAS, 300, null);

    private PartnerCredentialService credentialService;
    private HmacSignatureFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        credentialService = Mockito.mock(PartnerCredentialService.class);
        when(credentialService.findByApiKey(API_KEY)).thenReturn(Mono.just(CREDS));
        when(credentialService.findByApiKey(argThat(k -> k != null && !k.equals(API_KEY))))
                .thenReturn(Mono.empty());

        Clock fixed = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        filter = new HmacSignatureFilter(credentialService, fixed,
                HmacSignatureFilter.DEFAULT_CLOCK_SKEW_SECONDS);

        // Chain that always succeeds (downstream is never invoked in rejection cases)
        chain = exchange -> Mono.empty();
    }

    // -------------------------------------------------------------------------
    // Valid signature
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Valid HMAC signature + fresh timestamp: request is passed to chain")
    void validSignatureAndTimestamp_passesChain() {
        byte[] body = "{\"send_currency\":\"USD\"}".getBytes(StandardCharsets.UTF_8);
        String canonical = HmacSignatureVerifier.buildCanonicalString(
                "POST", "/v1/rates", VALID_TIMESTAMP, body);
        String sig = HmacSignatureVerifier.computeHmac(SECRET, canonical);

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/v1/rates")
                .header("X-API-Key", API_KEY)
                .header("X-Timestamp", VALID_TIMESTAMP)
                .header("X-Signature", sig)
                .body(new String(body, StandardCharsets.UTF_8));
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        // A MockServerWebExchange has null status when no error response is written —
        // the chain completed successfully without setting any error status code.
        assertNull(exchange.getResponse().getStatusCode(),
                "Valid request must not produce an error response status");
        assertEquals(PARTNER_ID, exchange.getAttributes().get(HmacSignatureFilter.ATTR_PARTNER_ID));
    }

    // -------------------------------------------------------------------------
    // Invalid signature (tampered body)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Tampered body: signature mismatch → 401 INVALID_SIGNATURE")
    void tamperedBody_returns401() {
        byte[] originalBody  = "{\"send_currency\":\"USD\"}".getBytes(StandardCharsets.UTF_8);
        byte[] tamperedBody  = "{\"send_currency\":\"GBP\"}".getBytes(StandardCharsets.UTF_8);

        String canonical = HmacSignatureVerifier.buildCanonicalString(
                "POST", "/v1/rates", VALID_TIMESTAMP, originalBody);
        String sig = HmacSignatureVerifier.computeHmac(SECRET, canonical);

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/v1/rates")
                .header("X-API-Key", API_KEY)
                .header("X-Timestamp", VALID_TIMESTAMP)
                .header("X-Signature", sig)
                .body(new String(tamperedBody, StandardCharsets.UTF_8));
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Wrong secret: signature mismatch → 401 INVALID_SIGNATURE")
    void wrongSecret_returns401() {
        byte[] body = "{\"amount\":\"100.00\"}".getBytes(StandardCharsets.UTF_8);
        String canonical = HmacSignatureVerifier.buildCanonicalString(
                "POST", "/v1/payments/initiate", VALID_TIMESTAMP, body);
        // Sign with a different secret
        String wrongSig = HmacSignatureVerifier.computeHmac("wrong_secret", canonical);

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/v1/payments/initiate")
                .header("X-API-Key", API_KEY)
                .header("X-Timestamp", VALID_TIMESTAMP)
                .header("X-Signature", wrongSig)
                .body(new String(body, StandardCharsets.UTF_8));
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    // -------------------------------------------------------------------------
    // Expired / future timestamp
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Timestamp too old (> clock-skew window) → 401 EXPIRED_TIMESTAMP")
    void expiredTimestamp_returns401() {
        // 6 minutes in the past — beyond the default 5-minute window
        String staleTs = "2026-06-15T11:54:00.000Z";

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/v1/rates")
                .header("X-API-Key", API_KEY)
                .header("X-Timestamp", staleTs)
                .header("X-Signature", "irrelevant_sig")
                .body("");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        // Credential lookup must NOT have occurred — timestamp check happens first.
        verify(credentialService, never()).findByApiKey(anyString());
    }

    @Test
    @DisplayName("Timestamp too far in the future (> clock-skew window) → 401 EXPIRED_TIMESTAMP")
    void futureTimestamp_returns401() {
        // 6 minutes in the future
        String futureTs = "2026-06-15T12:06:00.000Z";

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/v1/balance")
                .header("X-API-Key", API_KEY)
                .header("X-Timestamp", futureTs)
                .header("X-Signature", "irrelevant_sig")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verify(credentialService, never()).findByApiKey(anyString());
    }

    @Test
    @DisplayName("Missing X-Timestamp → 401 EXPIRED_TIMESTAMP")
    void missingTimestamp_returns401() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/v1/rates")
                .header("X-API-Key", API_KEY)
                .header("X-Signature", "irrelevant_sig")
                .body("");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        verify(credentialService, never()).findByApiKey(anyString());
    }

    @Test
    @DisplayName("Unparseable X-Timestamp → 401 EXPIRED_TIMESTAMP")
    void unparseableTimestamp_returns401() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/v1/rates")
                .header("X-API-Key", API_KEY)
                .header("X-Timestamp", "not-a-date")
                .header("X-Signature", "irrelevant_sig")
                .body("");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    // -------------------------------------------------------------------------
    // Missing / unknown API key
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Missing X-API-Key → 401 INVALID_API_KEY")
    void missingApiKey_returns401() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/v1/rates")
                .header("X-Timestamp", VALID_TIMESTAMP)
                .header("X-Signature", "irrelevant_sig")
                .body("");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    @DisplayName("Unknown X-API-Key → 401 INVALID_API_KEY")
    void unknownApiKey_returns401() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/v1/rates")
                .header("X-API-Key", "pk_unknown")
                .header("X-Timestamp", VALID_TIMESTAMP)
                .header("X-Signature", "irrelevant_sig")
                .body("");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    // -------------------------------------------------------------------------
    // Clock-skew boundary: timestamp exactly at the edge is still accepted
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Timestamp exactly at clock-skew boundary (300 s ago) is accepted")
    void timestampAtBoundary_isAccepted() {
        // Exactly 300 seconds before FIXED_NOW = 2026-06-15T11:55:00Z
        String boundaryTs = "2026-06-15T11:55:00.000Z";

        byte[] body = new byte[0];
        String canonical = HmacSignatureVerifier.buildCanonicalString(
                "GET", "/v1/balance", boundaryTs, body);
        String sig = HmacSignatureVerifier.computeHmac(SECRET, canonical);

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/v1/balance")
                .header("X-API-Key", API_KEY)
                .header("X-Timestamp", boundaryTs)
                .header("X-Signature", sig)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        // Should pass (delta = exactly 300 s, compareTo returns 0, not > 0)
        assertNull(exchange.getResponse().getStatusCode(),
                "Request at exact boundary should not be rejected");
    }

    // -------------------------------------------------------------------------
    // Filter order
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Filter order constant is 4 (after PartnerIpAllowlistFilter=2, MtlsFingerprintFilter=3)")
    void filterOrder_is4() {
        assertEquals(4, filter.getOrder());
        assertEquals(4, HmacSignatureFilter.ORDER);
    }
}
