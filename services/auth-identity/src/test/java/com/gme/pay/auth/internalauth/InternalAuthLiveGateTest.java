package com.gme.pay.auth.internalauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.auth.testsupport.TestInternalAuth;
import com.gme.pay.internalauth.InternalAuthHeaders;
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
 * T0-2 — proves the internal-auth gate is armed <b>by the service's own shipped configuration</b>,
 * not by a deployment env var. Until T0-2 {@code gmepay.internal-auth.enabled} defaulted to
 * {@code false}, so an unconfigured auth-identity minted JWTs and issued partner API keys to any
 * anonymous caller who could reach the port; docker-compose and Helm happening to set the flag was
 * the only thing hiding it.
 *
 * <p>Runs the whole service on a real port with {@link TestRestTemplate} (deliberately NOT MockMvc
 * with default headers, and deliberately without a {@code properties=} override of the gate): the
 * only thing this context adds over a production boot is the fixture secret, so a pass here is
 * evidence about the deployed perimeter.
 *
 * @see com.gme.pay.auth.config.InternalAuthEnforcedConfigTest for the fail-closed startup proof
 * @see InternalAuthGateTest for the filter-level unit proof over the RBAC/approval controllers
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Own H2 instance: the "correct credential reaches the handler" cases genuinely execute
        // against the real controllers, so POST /internal/auth/keys really does mint api_key rows.
        // The service's default URL (jdbc:h2:mem:authid;DB_CLOSE_DELAY=-1) is shared by every test
        // context in the JVM, and those rows would otherwise leak into the issuance-service slices.
        properties = "spring.datasource.url=jdbc:h2:mem:authid_gate;MODE=PostgreSQL;"
                + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE")
@ActiveProfiles("test")
class InternalAuthLiveGateTest {

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

    /** Every internal-only route, as (method, path, body). */
    static Stream<Arguments> guardedEndpoints() {
        return Stream.of(
                // --- identity minting: the worst of them ---
                Arguments.of(HttpMethod.POST, "/internal/auth/token/issue",
                        "{\"subject\":\"attacker\",\"permissions\":[\"rbac.manage\"]}"),
                Arguments.of(HttpMethod.POST, "/internal/auth/token/verify", "{\"token\":\"x\"}"),
                // --- partner machine-credential issuance (T1-1's real credentials) ---
                Arguments.of(HttpMethod.POST, "/internal/auth/keys",
                        "{\"partnerId\":42,\"partnerCode\":\"GMEREMIT\",\"environment\":\"PRODUCTION\","
                        + "\"purpose\":\"API\",\"keyPrefix\":\"pk_live_\",\"secretPrefix\":\"sk_live_\"}"),
                Arguments.of(HttpMethod.GET, "/internal/auth/keys", null),
                Arguments.of(HttpMethod.POST, "/internal/auth/keys/1/revoke", "{}"),
                Arguments.of(HttpMethod.POST, "/internal/auth/keys/rotate", "{\"keyId\":\"1\"}"),
                Arguments.of(HttpMethod.POST, "/internal/auth/keys/resolve", "{\"apiKey\":\"pk_live_x\"}"),
                Arguments.of(HttpMethod.POST, "/internal/auth/verify",
                        "{\"apiKey\":\"pk_live_x\",\"signature\":\"y\"}"),
                // --- RBAC resolution + catalogue management ---
                Arguments.of(HttpMethod.POST, "/v1/rbac/resolve", "{\"username\":\"x\"}"),
                Arguments.of(HttpMethod.GET, "/v1/rbac/permissions", null),
                Arguments.of(HttpMethod.POST, "/v1/rbac/permissions",
                        "{\"code\":\"x.y\",\"description\":\"d\"}"),
                Arguments.of(HttpMethod.GET, "/v1/rbac/roles", null),
                Arguments.of(HttpMethod.GET, "/v1/rbac/principals/1/permissions", null),
                Arguments.of(HttpMethod.GET, "/v1/rbac/constraints", null),
                // --- approval decisions (forgeable via X-Gme-Permissions without the gate) ---
                Arguments.of(HttpMethod.GET, "/v1/approvals", null),
                Arguments.of(HttpMethod.POST, "/v1/approvals/1/approve", "{}"),
                Arguments.of(HttpMethod.POST, "/v1/approvals/1/reject", "{}"));
    }

    @ParameterizedTest(name = "no credential → 401: {0} {1}")
    @MethodSource("guardedEndpoints")
    @DisplayName("no credential → 401 on every internal-only endpoint (default shipped config)")
    void noCredentialIsRefused(HttpMethod method, String path, String body) {
        ResponseEntity<String> res = exchange(method, path, body, null);
        assertThat(res.getStatusCode())
                .as("%s %s must not be servable anonymously", method, path)
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody())
                .as("%s %s must be refused by the internal-auth gate, not incidentally", method, path)
                .contains(GATE_REFUSAL);
    }

    @ParameterizedTest(name = "wrong credential → 401: {0} {1}")
    @MethodSource("guardedEndpoints")
    @DisplayName("wrong credential → 401 (a guessed/stale token buys nothing)")
    void wrongCredentialIsRefused(HttpMethod method, String path, String body) {
        ResponseEntity<String> res =
                exchange(method, path, body, TestInternalAuth.SECRET + "-tampered");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).contains(GATE_REFUSAL);
    }

    @ParameterizedTest(name = "correct credential → past the gate: {0} {1}")
    @MethodSource("guardedEndpoints")
    @DisplayName("correct credential → the gate lets the trusted caller through")
    void correctCredentialReachesHandler(HttpMethod method, String path, String body) {
        // Only the gate is under test. A handler may legitimately answer any 4xx of its own — 400
        // for a hand-written payload, 404 for an unknown id, and 401 from the *RBAC* layer on
        // /v1/approvals/{id}/{approve,reject}, which additionally demand stamped operator claims.
        // So the assertion is on the gate's own refusal body, not on the status alone.
        // (a bodiless 2xx such as 204 answers with null — that is a pass, not a refusal)
        String responseBody = exchange(method, path, body, TestInternalAuth.SECRET).getBody();
        assertThat(responseBody == null ? "" : responseBody)
                .as("%s %s must not be refused by the internal-auth gate for a trusted caller",
                        method, path)
                .doesNotContain(GATE_REFUSAL);
    }

    @Test
    @DisplayName("a blank token header is not a credential")
    void blankTokenIsRefused() {
        assertThat(exchange(HttpMethod.GET, "/v1/rbac/permissions", null, "  ").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("an anonymous JWT-mint attempt returns the ApiError envelope and no token")
    void mintAttemptLeaksNothing() {
        ResponseEntity<String> res = exchange(HttpMethod.POST, "/internal/auth/token/issue",
                "{\"subject\":\"attacker\",\"permissions\":[\"rbac.manage\"]}", null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).contains("UNAUTHORIZED")
                .doesNotContain("accessToken").doesNotContain("eyJ");
    }

    @Test
    @DisplayName("container probes stay anonymous; /actuator/metrics and /v3/api-docs do not")
    void probesStayOpenButIntrospectionDoesNot() {
        assertThat(exchange(HttpMethod.GET, "/actuator/health", null, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(HttpMethod.GET, "/actuator/health/readiness", null, null).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchange(HttpMethod.GET, "/actuator/metrics", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(HttpMethod.GET, "/v3/api-docs", null, null).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
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
