package com.gme.pay.prefunding.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.internalauth.InternalAuthHeaders;
import com.gme.pay.prefunding.persistence.PartnerBalanceRepository;
import com.gme.pay.prefunding.testsupport.TestInternalAuth;
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
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * T0-5 / CISO#6 — proves the service-to-service internal-auth gate actually fronts <b>every</b>
 * prefunding endpoint that moves or reads partner float.
 *
 * <p>Runs the whole service on a real port with {@link TestRestTemplate} (deliberately NOT MockMvc
 * with default headers): requests traverse the real servlet filter chain exactly as a network caller
 * would, so a pass here is evidence about the deployed perimeter, not about test wiring.
 *
 * <p>Before this gate, {@code POST /internal/v1/prefunding/{id}/deduct} and
 * {@code POST /v1/prefunding/{id}/credit} — debit and credit of any partner's float — answered any
 * anonymous caller who could reach the port, and the latter is additionally published through the
 * api-gateway's {@code /v1/prefunding/**} route.
 *
 * @see com.gme.pay.prefunding.config.InternalAuthEnforcedConfigTest for the fail-closed startup proof
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "gmepay.outbox.poll-ms=3600000")
@ActiveProfiles("test")
class InternalAuthGateTest {

    private static final String PARTNER = "GATEPARTNER";

    @Autowired private TestRestTemplate rest;
    @Autowired private PartnerBalanceRepository balances;

    @BeforeEach
    void reset() {
        balances.deleteAll();
        // TestRestTemplate defaults to SimpleClientHttpRequestFactory (HttpURLConnection), which
        // throws HttpRetryException ("cannot retry due to server authentication") instead of
        // surfacing a 401 whose request carried a body — exactly the case under test here. The JDK
        // HttpClient factory has no such re-authentication behaviour and needs no extra dependency.
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    /** Every money-moving / float-reading route, as (method, path, body). */
    static Stream<Arguments> guardedEndpoints() {
        return Stream.of(
                // --- internal service-to-service surface (PrefundingInternalController) ---
                Arguments.of(HttpMethod.POST, "/internal/v1/prefunding/" + PARTNER + "/deduct",
                        "{\"idempotencyKey\":\"t1\",\"amountUsd\":\"10.00\"}"),
                Arguments.of(HttpMethod.POST, "/internal/v1/prefunding/" + PARTNER + "/reverse",
                        "{\"txnRef\":\"t1\"}"),
                Arguments.of(HttpMethod.POST, "/internal/v1/prefunding/" + PARTNER + "/reserve",
                        "{\"idempotencyKey\":\"t2\",\"amountUsd\":\"10.00\"}"),
                Arguments.of(HttpMethod.POST, "/internal/v1/prefunding/" + PARTNER + "/release",
                        "{\"idempotencyKey\":\"t2\"}"),
                Arguments.of(HttpMethod.PUT, "/internal/v1/prefunding/" + PARTNER + "/credit-limit",
                        "{\"creditLimitUsd\":\"500.00\"}"),
                // --- the /v1 surface, which the api-gateway additionally publishes ---
                Arguments.of(HttpMethod.POST, "/v1/prefunding/provision",
                        "{\"partnerCode\":\"" + PARTNER + "\",\"openingBalanceUsd\":\"100.00\","
                        + "\"lowBalanceThresholdUsd\":\"10.00\"}"),
                Arguments.of(HttpMethod.POST, "/v1/prefunding/" + PARTNER + "/credit",
                        "{\"amount\":\"1000000.00\"}"),
                Arguments.of(HttpMethod.POST, "/v1/prefunding/" + PARTNER + "/deduct",
                        "{\"txnRef\":\"t3\",\"amount\":\"10.00\"}"),
                Arguments.of(HttpMethod.POST, "/v1/prefunding/" + PARTNER + "/reverse",
                        "{\"txnRef\":\"t3\"}"),
                Arguments.of(HttpMethod.POST, "/v1/prefunding/" + PARTNER + "/reserve",
                        "{\"txnRef\":\"t4\",\"amount\":\"10.00\"}"),
                Arguments.of(HttpMethod.POST, "/v1/prefunding/" + PARTNER + "/capture",
                        "{\"txnRef\":\"t4\",\"amount\":\"10.00\"}"),
                Arguments.of(HttpMethod.POST, "/v1/prefunding/" + PARTNER + "/release",
                        "{\"txnRef\":\"t4\",\"amount\":\"10.00\"}"),
                Arguments.of(HttpMethod.POST, "/v1/prefunding/" + PARTNER + "/cumulative-charge",
                        "{\"txnRef\":\"t5\",\"amountUsd\":\"10.00\"}"),
                Arguments.of(HttpMethod.POST, "/v1/prefunding/" + PARTNER + "/cumulative-reverse",
                        "{\"txnRef\":\"t5\"}"),
                Arguments.of(HttpMethod.PUT, "/v1/prefunding/" + PARTNER + "/credit-limit",
                        "{\"creditLimit\":\"500.00\"}"),
                Arguments.of(HttpMethod.GET, "/v1/prefunding/" + PARTNER + "/balance", null),
                Arguments.of(HttpMethod.GET, "/v1/prefunding/" + PARTNER + "/alerts", null),
                Arguments.of(HttpMethod.GET, "/v1/prefunding/" + PARTNER + "/deductions", null));
    }

    @ParameterizedTest(name = "no credential → 401: {0} {1}")
    @MethodSource("guardedEndpoints")
    @DisplayName("no credential → 401 on every money-moving / float-reading endpoint")
    void noCredentialIsRefused(HttpMethod method, String path, String body) {
        assertThat(exchange(method, path, body, null).getStatusCode())
                .as("%s %s must not be servable anonymously", method, path)
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest(name = "wrong credential → 401: {0} {1}")
    @MethodSource("guardedEndpoints")
    @DisplayName("wrong credential → 401 (a guessed/stale token buys nothing)")
    void wrongCredentialIsRefused(HttpMethod method, String path, String body) {
        assertThat(exchange(method, path, body, TestInternalAuth.SECRET + "-tampered").getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @ParameterizedTest(name = "correct credential → reaches the handler: {0} {1}")
    @MethodSource("guardedEndpoints")
    @DisplayName("correct credential → the gate lets the request through to the handler")
    void correctCredentialReachesHandler(HttpMethod method, String path, String body) {
        // The gate is the only thing under test: a handler may legitimately answer 4xx business
        // codes (404 unknown partner, 402 insufficient float). What must NOT come back is 401 —
        // that would mean the trusted caller was refused.
        assertThat(exchange(method, path, body, TestInternalAuth.SECRET).getStatusCode())
                .isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("correct credential → 201 CREATED on a real provisioning, then 200 on the balance read")
    void correctCredentialGetsRealSuccess() {
        ResponseEntity<String> provisioned = exchange(HttpMethod.POST, "/v1/prefunding/provision",
                "{\"partnerCode\":\"" + PARTNER + "\",\"openingBalanceUsd\":\"100.00\","
                + "\"lowBalanceThresholdUsd\":\"10.00\"}", TestInternalAuth.SECRET);
        assertThat(provisioned.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> balance = exchange(HttpMethod.GET,
                "/v1/prefunding/" + PARTNER + "/balance", null, TestInternalAuth.SECRET);
        assertThat(balance.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(balance.getBody()).contains("100.00");
    }

    @Test
    @DisplayName("a blank token header is not a credential")
    void blankTokenIsRefused() {
        assertThat(exchange(HttpMethod.GET, "/v1/prefunding/" + PARTNER + "/balance", null, "  ")
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("401 body is the standard ApiError envelope, and leaks nothing about the balance")
    void refusalUsesStandardErrorEnvelope() {
        ResponseEntity<String> res =
                exchange(HttpMethod.GET, "/v1/prefunding/" + PARTNER + "/balance", null, null);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody()).contains("UNAUTHORIZED").doesNotContain("balance\":");
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
