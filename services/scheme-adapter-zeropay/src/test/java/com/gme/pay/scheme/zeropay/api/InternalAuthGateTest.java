package com.gme.pay.scheme.zeropay.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.internalauth.InternalAuthHeaders;
import com.gme.pay.scheme.zeropay.testsupport.TestInternalAuth;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;

/**
 * T0-2 — proves the internal-auth gate fronts <b>every</b> {@code /internal/scheme/zeropay}
 * endpoint. Before this change the adapter shipped no {@code gmepay.internal-auth} configuration at
 * all, so {@code POST /submit} and {@code POST /cpm} — which authorise and commit real money at the
 * scheme — plus {@code POST /cancel} and the settlement prerequisite projection
 * {@code GET /registration-status} answered any anonymous caller who could reach port 8090.
 *
 * <p>Runs the whole service on a real port with {@link TestRestTemplate} (deliberately NOT MockMvc
 * with default headers): requests traverse the real servlet filter chain exactly as a network caller
 * would, so a pass here is evidence about the deployed perimeter rather than about test wiring. The
 * only thing this context adds over a production boot is the fixture secret.
 *
 * @see com.gme.pay.scheme.zeropay.config.InternalAuthEnforcedConfigTest for the fail-closed proof
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class InternalAuthGateTest {

    /** The exact message {@code InternalAuthFilter} writes — the only 401 this test is about. */
    private static final String GATE_REFUSAL = "internal service authentication required";

    @Autowired private TestRestTemplate rest;

    @BeforeEach
    void useJdkClient() {
        // TestRestTemplate's default SimpleClientHttpRequestFactory (HttpURLConnection) throws
        // HttpRetryException ("cannot retry due to server authentication") instead of surfacing a
        // 401 whose request carried a body — exactly the case under test here.
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    /** Every internal scheme route, as (method, path, body). */
    static Stream<Arguments> guardedEndpoints() {
        return Stream.of(
                // --- money movement at the scheme ---
                Arguments.of(HttpMethod.POST, "/internal/scheme/zeropay/submit",
                        "{\"partnerTxnRef\":\"gate-1\",\"amountKrw\":\"1000\",\"qrPayload\":\"x\"}"),
                Arguments.of(HttpMethod.POST, "/internal/scheme/zeropay/cpm",
                        "{\"partnerTxnRef\":\"gate-2\",\"amountKrw\":\"1000\",\"cpmToken\":\"x\"}"),
                Arguments.of(HttpMethod.POST, "/internal/scheme/zeropay/cancel",
                        "{\"partnerTxnRef\":\"gate-1\",\"schemeTxnRef\":\"x\"}"),
                // --- disclosure of GME's prepaid position with the scheme ---
                Arguments.of(HttpMethod.POST, "/internal/scheme/zeropay/balance-check",
                        "{\"amountKrw\":\"1000\"}"),
                Arguments.of(HttpMethod.GET, "/internal/scheme/zeropay/health", null),
                // --- the settlement prerequisite gate ---
                Arguments.of(HttpMethod.GET,
                        "/internal/scheme/zeropay/registration-status?businessDate=2026-07-28", null));
    }

    @ParameterizedTest(name = "no credential → 401: {0} {1}")
    @MethodSource("guardedEndpoints")
    @DisplayName("no credential → 401 on every internal scheme endpoint")
    void noCredentialIsRefused(HttpMethod method, String path, String body) {
        ResponseEntity<String> res = exchange(method, path, body, null);
        assertThat(res.getStatusCode())
                .as("%s %s must not be servable anonymously", method, path)
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).contains(GATE_REFUSAL);
    }

    @ParameterizedTest(name = "wrong credential → 401: {0} {1}")
    @MethodSource("guardedEndpoints")
    @DisplayName("wrong credential → 401 (a guessed/stale token buys nothing)")
    void wrongCredentialIsRefused(HttpMethod method, String path, String body) {
        assertThat(exchange(method, path, body, TestInternalAuth.SECRET + "-tampered").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest(name = "correct credential → past the gate: {0} {1}")
    @MethodSource("guardedEndpoints")
    @DisplayName("correct credential → the gate lets the trusted caller through")
    void correctCredentialReachesHandler(HttpMethod method, String path, String body) {
        // Only the gate is under test: a handler may legitimately answer its own 4xx/5xx (400 for a
        // hand-written payload, 502 when the scheme sim is not running). What must never appear is
        // the gate's own refusal. A bodiless answer is a pass.
        String responseBody = exchange(method, path, body, TestInternalAuth.SECRET).getBody();
        assertThat(responseBody == null ? "" : responseBody)
                .as("%s %s must not be refused by the internal-auth gate for a trusted caller",
                        method, path)
                .doesNotContain(GATE_REFUSAL);
    }

    @Test
    @DisplayName("a blank token header is not a credential")
    void blankTokenIsRefused() {
        assertThat(exchange(HttpMethod.GET, "/internal/scheme/zeropay/health", null, "  ")
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the registration-status refusal is the ApiError envelope and leaks no verdict")
    void refusalUsesStandardErrorEnvelope() {
        ResponseEntity<String> res = exchange(HttpMethod.GET,
                "/internal/scheme/zeropay/registration-status?businessDate=2026-07-28", null, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).contains("UNAUTHORIZED")
                .doesNotContain("zp0011Succeeded").doesNotContain("zp0012Received");
    }

    @Test
    @DisplayName("the registration-status projection really answers a trusted caller (200 + fields)")
    void trustedCallerGetsTheRealProjection() {
        ResponseEntity<String> res = exchange(HttpMethod.GET,
                "/internal/scheme/zeropay/registration-status?businessDate=2026-07-28", null,
                TestInternalAuth.SECRET);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).contains("zp0011Succeeded").contains("zp0012Received");
    }

    @Test
    @DisplayName("/v3/api-docs is gated; actuator is not on the app port at all (separate mgmt port)")
    void introspectionIsGated() {
        assertThat(exchange(HttpMethod.GET, "/v3/api-docs", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(HttpMethod.GET, "/v3/api-docs", null, TestInternalAuth.SECRET)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        // This service runs the actuator on its own management port (application.yml
        // management.server.port=8091), a CHILD context that servlet filters registered here do not
        // reach. So container probes are unaffected by the gate — and equally, the gate cannot
        // protect /actuator/metrics on 8091. Not exposing that port is a deployment concern; recorded
        // here so the limitation is visible rather than assumed away.
        assertThat(exchange(HttpMethod.GET, "/actuator/health", null, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("/__data table dumps: 401 anonymously (not even fingerprintable), 404 to a trusted "
            + "caller because the devtools flag is off")
    void devDataSurfaceIsGatedAndAbsentByDefault() {
        assertThat(exchange(HttpMethod.GET, "/__data/tables", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(HttpMethod.GET, "/__data/tables", null, TestInternalAuth.SECRET)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<String> exchange(HttpMethod method, String path, String body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.set(InternalAuthHeaders.INTERNAL_TOKEN, token);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }
}
