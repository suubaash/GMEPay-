package com.gme.pay.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.internalauth.InternalAuthHeaders;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * <b>T3-2 on the internet-facing edge.</b> api-gateway is the only service published through the
 * ingress, and its {@code SecurityConfig} used to {@code permitAll} {@code /actuator/prometheus} while
 * {@code build.gradle} claimed the endpoint existed. It did not — no Micrometer registry was on any
 * classpath — so the {@code permitAll} was harmless only by accident. Making the endpoint real without
 * gating it would have converted a dead comment into a live anonymous disclosure of route names,
 * per-partner request volumes and latency histograms.
 *
 * <p>Both halves are asserted here, on a real reactive server:
 *
 * <ol>
 *   <li>the endpoint <b>exists</b> and serves the Prometheus exposition format;</li>
 *   <li>it is <b>not anonymous</b> — a scrape must present the platform internal token
 *       ({@code X-Gme-Internal}), and with no secret configured it is fail-closed (401 for everyone,
 *       stricter than the servlet services' opportunistic gate, because this is the edge);</li>
 *   <li>container health probes stay anonymous, so gating metrics cannot break liveness.</li>
 * </ol>
 *
 * <p>Context recipe (Redis/JWKS exclusions + stub {@link ReactiveJwtDecoder}) is the same one
 * {@link GatewayRouteTableTest} established, so no Keycloak or Redis is contacted.
 * {@code @AutoConfigureObservability} is required because Spring Boot's test support switches metrics
 * exporters off inside {@code @SpringBootTest}; that is a harness default, not the shipped config.
 */
class PrometheusScrapeGateTest {

    private static final String SECRET = "test-fixture-internal-token-not-a-deployment-secret";

    /** Supplies a decoder bean so SecurityConfig builds without reaching Keycloak's JWKS. */
    @TestConfiguration
    static class JwtDecoderStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return Mockito.mock(ReactiveJwtDecoder.class);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @AutoConfigureObservability(tracing = false)
    @Import(JwtDecoderStub.class)
    @TestPropertySource(properties = {
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.security.oauth2.resource.reactive."
                    + "ReactiveOAuth2ResourceServerAutoConfiguration",
            "management.health.redis.enabled=false",
            "gmepay.internal-auth.secret=" + SECRET,
    })
    @DisplayName("internal token configured (deployment posture)")
    class Gated {

        @Autowired private WebTestClient autoConfigured;

        private WebTestClient client;

        /**
         * WebTestClient's default response timeout is 5s. The FIRST Prometheus scrape materialises every
         * meter in the registry, which on a loaded CI machine can exceed that — the assertion would then
         * fail as a timeout rather than telling us anything about the endpoint. Widened deliberately;
         * this changes no production behaviour.
         */
        @BeforeEach
        void widenResponseTimeout() {
            client = autoConfigured.mutate().responseTimeout(Duration.ofSeconds(30)).build();
        }

        @Test
        @DisplayName("the scrape serves Prometheus output when the internal token is presented")
        void tokenGetsTheScrape() {
            byte[] body = client.get().uri(SecurityConfig.PROMETHEUS_PATH)
                    .header(InternalAuthHeaders.INTERNAL_TOKEN, SECRET)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().returnResult().getResponseBody();

            assertThat(body).isNotNull();
            assertThat(new String(body, java.nio.charset.StandardCharsets.UTF_8))
                    .as("this path 404'd before T3-2 — no Micrometer registry on any classpath")
                    .contains("jvm_memory_used_bytes")
                    .contains("application=\"api-gateway\"");
        }

        @Test
        @DisplayName("no token / wrong token → 401, never an anonymous scrape")
        void anonymousScrapeIsRefused() {
            client.get().uri(SecurityConfig.PROMETHEUS_PATH)
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
            client.get().uri(SecurityConfig.PROMETHEUS_PATH)
                    .header(InternalAuthHeaders.INTERNAL_TOKEN, "wrong-token")
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("container probes stay anonymous")
        void probesStayAnonymous() {
            client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @AutoConfigureObservability(tracing = false)
    @Import(JwtDecoderStub.class)
    @TestPropertySource(properties = {
            "spring.autoconfigure.exclude="
                    + "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration,"
                    + "org.springframework.boot.autoconfigure.security.oauth2.resource.reactive."
                    + "ReactiveOAuth2ResourceServerAutoConfiguration",
            "management.health.redis.enabled=false",
    })
    @DisplayName("no internal token configured (fail-closed)")
    class FailClosed {

        @Autowired private WebTestClient autoConfigured;

        private WebTestClient client;

        /**
         * WebTestClient's default response timeout is 5s. The FIRST Prometheus scrape materialises every
         * meter in the registry, which on a loaded CI machine can exceed that — the assertion would then
         * fail as a timeout rather than telling us anything about the endpoint. Widened deliberately;
         * this changes no production behaviour.
         */
        @BeforeEach
        void widenResponseTimeout() {
            client = autoConfigured.mutate().responseTimeout(Duration.ofSeconds(30)).build();
        }

        @Test
        @DisplayName("a blank secret refuses EVERY scraper rather than serving anonymously")
        void blankSecretIsFailClosed() {
            client.get().uri(SecurityConfig.PROMETHEUS_PATH)
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
            client.get().uri(SecurityConfig.PROMETHEUS_PATH)
                    .header(InternalAuthHeaders.INTERNAL_TOKEN, "")
                    .exchange()
                    .expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
            // ...while the edge keeps answering probes.
            client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        }
    }
}
