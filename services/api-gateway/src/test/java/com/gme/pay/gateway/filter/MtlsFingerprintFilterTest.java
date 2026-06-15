package com.gme.pay.gateway.filter;

import com.gme.pay.gateway.partner.PartnerCredentialService;
import com.gme.pay.gateway.partner.PartnerCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MtlsFingerprintFilter}.
 *
 * <p>Tests cover: disabled mode (no-op), enabled mode with matching cert, mismatching cert,
 * missing cert header, partner without a registered cert, and JWT (no-API-key) traffic passthrough.
 * No Spring context is started.
 */
class MtlsFingerprintFilterTest {

    private static final String API_KEY     = "pk_test_abc";
    private static final String PARTNER_ID  = "partner_test_001";
    private static final String FINGERPRINT = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899";

    private static final PartnerCredentials CREDS_WITH_CERT = new PartnerCredentials(
            PARTNER_ID, API_KEY, "sk_test_xyz", List.of(),
            PartnerCredentials.PartnerType.OVERSEAS, 300, FINGERPRINT);

    private static final PartnerCredentials CREDS_NO_CERT = new PartnerCredentials(
            "partner_test_002", "pk_test_no_mtls", "sk_test_no_mtls", List.of(),
            PartnerCredentials.PartnerType.OVERSEAS, 300, null);

    private PartnerCredentialService credentialService;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        credentialService = Mockito.mock(PartnerCredentialService.class);
        when(credentialService.findByApiKey(API_KEY)).thenReturn(Mono.just(CREDS_WITH_CERT));
        when(credentialService.findByApiKey("pk_test_no_mtls"))
                .thenReturn(Mono.just(CREDS_NO_CERT));
        when(credentialService.findByApiKey("pk_unknown")).thenReturn(Mono.empty());
        chain = exchange -> Mono.empty();
    }

    // -------------------------------------------------------------------------
    // When mTLS is disabled (default)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("mTLS disabled: filter is a no-op, any request passes through")
    void disabled_anyRequest_passesThrough() {
        MtlsFingerprintFilter filter = filterEnabled(false);

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/v1/rates")
                .header("X-API-Key", API_KEY)
                // Deliberately NO cert header
                .body("");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        assertNull(exchange.getResponse().getStatusCode(),
                "Disabled mTLS filter must not set any response status");
    }

    // -------------------------------------------------------------------------
    // mTLS enabled — matching fingerprint
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("mTLS enabled + correct fingerprint: request passes chain")
    void enabled_correctFingerprint_passesChain() {
        MtlsFingerprintFilter filter = filterEnabled(true);

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/v1/rates")
                .header("X-API-Key", API_KEY)
                .header(MtlsFingerprintFilter.DEFAULT_CERT_HEADER, FINGERPRINT)
                .body("");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        assertNull(exchange.getResponse().getStatusCode(),
                "Valid cert fingerprint must not produce an error response");
    }

    @Test
    @DisplayName("mTLS enabled + correct fingerprint in upper-case: accepted (case-insensitive)")
    void enabled_correctFingerprintUpperCase_passesChain() {
        MtlsFingerprintFilter filter = filterEnabled(true);

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/v1/rates")
                .header("X-API-Key", API_KEY)
                .header(MtlsFingerprintFilter.DEFAULT_CERT_HEADER, FINGERPRINT.toUpperCase())
                .body("");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        assertNull(exchange.getResponse().getStatusCode());
    }

    // -------------------------------------------------------------------------
    // mTLS enabled — mismatching fingerprint
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("mTLS enabled + wrong fingerprint → 401 MTLS_CERT_MISMATCH")
    void enabled_wrongFingerprint_returns401() {
        MtlsFingerprintFilter filter = filterEnabled(true);

        String wrongFingerprint = "0000000000000000000000000000000000000000000000000000000000000000";
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/v1/rates")
                .header("X-API-Key", API_KEY)
                .header(MtlsFingerprintFilter.DEFAULT_CERT_HEADER, wrongFingerprint)
                .body("");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    // -------------------------------------------------------------------------
    // mTLS enabled — missing cert header
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("mTLS enabled + cert header absent → 401 MTLS_CERT_MISSING")
    void enabled_missingCertHeader_returns401() {
        MtlsFingerprintFilter filter = filterEnabled(true);

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/v1/rates")
                .header("X-API-Key", API_KEY)
                // no cert header
                .body("");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    // -------------------------------------------------------------------------
    // mTLS enabled — partner without registered cert
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("mTLS enabled + partner has no registered cert → 401 MTLS_CERT_NOT_REGISTERED")
    void enabled_partnerNoCert_returns401() {
        MtlsFingerprintFilter filter = filterEnabled(true);

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/v1/rates")
                .header("X-API-Key", "pk_test_no_mtls")
                .header(MtlsFingerprintFilter.DEFAULT_CERT_HEADER, FINGERPRINT)
                .body("");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    // -------------------------------------------------------------------------
    // mTLS enabled — unknown API key (let HmacSignatureFilter 401 it)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("mTLS enabled + unknown API key: filter defers to HmacSignatureFilter (passes chain)")
    void enabled_unknownApiKey_defersToHmacFilter() {
        MtlsFingerprintFilter filter = filterEnabled(true);

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/v1/rates")
                .header("X-API-Key", "pk_unknown")
                .header(MtlsFingerprintFilter.DEFAULT_CERT_HEADER, FINGERPRINT)
                .body("");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        // No status set by this filter — HmacSignatureFilter handles the unknown key.
        assertNull(exchange.getResponse().getStatusCode());
    }

    // -------------------------------------------------------------------------
    // JWT (human) traffic — no API key present
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("mTLS enabled + no X-API-Key (JWT traffic): filter is a no-op")
    void enabled_noApiKey_jwtTraffic_passesThrough() {
        MtlsFingerprintFilter filter = filterEnabled(true);

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/v1/partners/123")
                .header("Authorization", "Bearer some.jwt.token")
                // no X-API-Key header
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        assertNull(exchange.getResponse().getStatusCode(),
                "JWT traffic without X-API-Key must not be affected by mTLS filter");
    }

    // -------------------------------------------------------------------------
    // Filter order
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Filter order constant is 3 (after PartnerIpAllowlistFilter=2, before HmacSignatureFilter=4)")
    void filterOrder_is3() {
        MtlsFingerprintFilter filter = filterEnabled(false);
        assertEquals(3, filter.getOrder());
        assertEquals(3, MtlsFingerprintFilter.ORDER);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private MtlsFingerprintFilter filterEnabled(boolean enabled) {
        return new MtlsFingerprintFilter(
                credentialService,
                enabled,
                MtlsFingerprintFilter.DEFAULT_CERT_HEADER);
    }
}
