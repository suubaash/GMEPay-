package com.gme.pay.payment.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.internalauth.InternalAuthHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * <b>T3-2 existence proof</b> for {@code /actuator/prometheus}, plus the T3-3 ops-alert query surface.
 *
 * <h2>The gap this pins</h2>
 * <p>{@code api-gateway/build.gradle} carried the comment "Actuator for /actuator/health +
 * /actuator/prometheus" and api-gateway's {@code SecurityConfig} even {@code permitAll}'d the path —
 * but no module had a Micrometer registry on its classpath and no service named {@code prometheus} in
 * {@code management.endpoints.web.exposure.include}, so the endpoint 404'd on all 19 services. The
 * platform's monitoring story was a comment. A test that boots the real service and asks for the real
 * path is the only thing that can keep that from happening again.
 *
 * <p>Two postures are asserted, because the fix must not open a new anonymous surface:
 *
 * <ul>
 *   <li><b>no secret configured</b> (a bare local run) — the endpoint EXISTS and serves the Prometheus
 *       text format;</li>
 *   <li><b>secret configured</b> (every real deployment) — the scrape requires the platform internal
 *       token, exactly like {@code /actuator/metrics}, while container probes stay anonymous.</li>
 * </ul>
 */
class PrometheusEndpointTest {

    private static final String SECRET = "test-fixture-internal-token-not-a-deployment-secret";

    private static ResponseEntity<String> get(TestRestTemplate rest, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set(InternalAuthHeaders.INTERNAL_TOKEN, token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** Bare local run: no internal secret, so introspection is not gated on this instance. */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @DisplayName("no internal secret configured")
    class Ungated {

        @Autowired private TestRestTemplate rest;

        @BeforeEach
        void useJdkClient() {
            rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        }

        @Test
        @DisplayName("/actuator/prometheus EXISTS and serves the Prometheus exposition format")
        void prometheusEndpointExists() {
            ResponseEntity<String> res = get(rest, "/actuator/prometheus", null);

            assertThat(res.getStatusCode())
                    .as("this returned 404 before T3-2 — no registry, and 'prometheus' was not in the "
                            + "exposure include list")
                    .isEqualTo(HttpStatus.OK);
            assertThat(res.getBody())
                    .as("must be real scrapeable output, not an empty 200")
                    .isNotBlank()
                    // Two families every Boot service with a Micrometer registry emits.
                    .contains("jvm_memory_used_bytes")
                    .contains("# TYPE");
        }

        @Test
        @DisplayName("metrics carry the application tag so one Prometheus can hold the whole fleet")
        void metricsAreTaggedWithTheServiceName() {
            assertThat(get(rest, "/actuator/prometheus", null).getBody())
                    .contains("application=\"payment-executor\"");
        }

        @Test
        @DisplayName("the durable ops-alert query is fail-closed: 401 even with no secret configured")
        void opsAlertQueryIsFailClosedWithoutASecret() {
            // A blank configured secret can never equal a presented one, so /internal/** refuses
            // EVERY caller rather than exposing partner decline rates anonymously.
            assertThat(get(rest, "/internal/ops/alerts", null).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(get(rest, "/internal/ops/alerts", SECRET).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    /** Every real deployment: the platform internal token is set. */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "gmepay.internal-auth.secret=" + SECRET)
    @DisplayName("internal secret configured (deployment posture)")
    class Gated {

        @Autowired private TestRestTemplate rest;

        @BeforeEach
        void useJdkClient() {
            rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        }

        @Test
        @DisplayName("the scrape requires the internal token")
        void prometheusRequiresTheInternalToken() {
            assertThat(get(rest, "/actuator/prometheus", null).getStatusCode())
                    .as("an anonymous scrape would leak per-partner payment counts and decline rates")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(get(rest, "/actuator/prometheus", "wrong-token").getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);

            ResponseEntity<String> authorized = get(rest, "/actuator/prometheus", SECRET);
            assertThat(authorized.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(authorized.getBody()).contains("jvm_memory_used_bytes");
        }

        @Test
        @DisplayName("container probes stay anonymous — gating metrics must not break liveness")
        void probesStayAnonymous() {
            assertThat(get(rest, "/actuator/health", null).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get(rest, "/actuator/health/liveness", null).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(get(rest, "/actuator/health/readiness", null).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("the ops-alert history is readable with the token and refused without it")
        void opsAlertQueryIsGatedNotBlocked() {
            assertThat(get(rest, "/internal/ops/alerts", null).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);

            ResponseEntity<String> res = get(rest, "/internal/ops/alerts?limit=5", SECRET);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody()).as("an empty history is a JSON array, not an error").isEqualTo("[]");
        }

        @Test
        @DisplayName("the partner-facing pay surface is not swept into the metrics gate")
        void paySurfaceUntouched() {
            assertThat(get(rest, "/v1/payments/does-not-exist", null).getStatusCode())
                    .isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
