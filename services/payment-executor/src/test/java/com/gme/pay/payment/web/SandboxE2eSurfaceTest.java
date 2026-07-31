package com.gme.pay.payment.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.gme.pay.internalauth.InternalAuthHeaders;
import com.gme.pay.payment.config.SandboxSurfaceInternalAuthConfig;
import com.gme.pay.payment.sandbox.E2eRunner;
import com.gme.pay.payment.sandbox.SelfPayClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * T0-5 / CISO#6 — the sandbox E2E <b>payment runner</b> perimeter.
 *
 * <p>{@code POST /v1/sandbox/e2e/run} executes a real authorize+capture over the real pay path
 * (prefunding debit, scheme call, ledger postings). It was previously an always-on, unauthenticated
 * endpoint, and the admin-ui proxied {@code /e2e/*} straight to it — i.e. reachable from wherever the
 * portal was reachable. Two independent controls are asserted here:
 *
 * <ol>
 *   <li><b>Off by default</b> — with no flag set the controller, {@link E2eRunner} and
 *       {@link SelfPayClient} beans do not exist, so the paths answer {@code 404}: there is no
 *       runner to reach, not merely a blocked one.</li>
 *   <li><b>Auth required when on</b> — enabling the flag does not by itself expose it; the surface
 *       is gated behind the shared internal-auth token, and the service refuses to start if the
 *       flag is on without a secret.</li>
 * </ol>
 */
class SandboxE2eSurfaceTest {

    private static final String SECRET = "test-fixture-internal-token-not-a-deployment-secret";

    private static ResponseEntity<String> get(TestRestTemplate rest, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set(InternalAuthHeaders.INTERNAL_TOKEN, token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /**
     * Drives the registered {@link com.gme.pay.internalauth.InternalAuthFilter} directly (the filter's
     * own {@code isProtected} / {@code secretMatches} are package-private in lib-errors, and asserting
     * on behaviour is the point anyway). True when the filter answered 401 itself instead of
     * delegating down the chain.
     */
    private static boolean refusedByFilter(com.gme.pay.internalauth.InternalAuthFilter filter,
                                           String uri, String token) throws Exception {
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        if (token != null) {
            request.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, token);
        }
        org.springframework.mock.web.MockHttpServletResponse response =
                new org.springframework.mock.web.MockHttpServletResponse();
        org.springframework.mock.web.MockFilterChain chain =
                new org.springframework.mock.web.MockFilterChain();
        filter.doFilter(request, response, chain);
        boolean delegated = chain.getRequest() != null;
        return !delegated && response.getStatus() == HttpStatus.UNAUTHORIZED.value();
    }

    /** Default posture — this is what a production deployment boots with. */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @DisplayName("flag absent (production default)")
    class Disabled {

        @Autowired private ApplicationContext ctx;
        @Autowired private TestRestTemplate rest;

        @BeforeEach
        void useJdkClient() {
            rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        }

        @Test
        @DisplayName("the runner does not exist as a bean at all")
        void runnerBeansAbsent() {
            assertThat(ctx.getBeansOfType(SandboxE2eController.class)).isEmpty();
            assertThat(ctx.getBeansOfType(E2eRunner.class)).isEmpty();
            assertThat(ctx.getBeansOfType(SelfPayClient.class)).isEmpty();
        }

        @Test
        @DisplayName("every sandbox path 404s — nothing to probe, with or without a token")
        void sandboxPathsAre404() {
            assertThat(get(rest, "/v1/sandbox/e2e/options", null).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(get(rest, "/v1/sandbox/e2e/runs", null).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(get(rest, "/v1/sandbox/e2e/runs/1", SECRET).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("the /__data table-dump dev tool is off too")
        void devDataToolIsOff() {
            assertThat(get(rest, "/__data/tables", null).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("the production pay surface is unaffected — no internal-auth gate on it")
        void productionSurfaceNotGated() {
            // /v1/payments/{id} with a nonsense id must reach the handler (4xx business answer),
            // NOT be refused 401 by an over-broad internal-auth filter.
            assertThat(get(rest, "/v1/payments/does-not-exist", null).getStatusCode())
                    .isNotEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(get(rest, "/actuator/health", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    /** Deliberately enabled sandbox deployment. */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"gmepay.sandbox.e2e.enabled=true",
                          "gmepay.internal-auth.secret=" + SECRET})
    @DisplayName("flag explicitly enabled")
    class Enabled {

        @Autowired private ApplicationContext ctx;
        @Autowired private TestRestTemplate rest;

        @BeforeEach
        void useJdkClient() {
            rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        }

        @Test
        @DisplayName("the runner now exists")
        void runnerBeanPresent() {
            assertThat(ctx.getBeansOfType(SandboxE2eController.class)).hasSize(1);
        }

        @Test
        @DisplayName("no credential → 401")
        void noCredentialRefused() {
            assertThat(get(rest, "/v1/sandbox/e2e/options", null).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(get(rest, "/v1/sandbox/e2e/runs", null).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("wrong credential → 401")
        void wrongCredentialRefused() {
            assertThat(get(rest, "/v1/sandbox/e2e/options", SECRET + "-tampered").getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(get(rest, "/v1/sandbox/e2e/options", "   ").getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("correct credential → 200")
        void correctCredentialAllowed() {
            ResponseEntity<String> res = get(rest, "/v1/sandbox/e2e/options", SECRET);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(res.getBody()).contains("NPR");
        }

        @Test
        @DisplayName("the POST that actually spends money is gated as well")
        void runIsGated() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
            ResponseEntity<String> res = rest.exchange("/v1/sandbox/e2e/run", HttpMethod.POST,
                    new HttpEntity<>("{\"country\":\"KR\",\"partner\":\"GMEREMIT\","
                            + "\"amount\":\"1000\",\"mpmType\":\"STATIC\"}", headers), String.class);
            assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("enabling the sandbox does not gate the production pay surface")
        void productionSurfaceStillUngated() {
            assertThat(get(rest, "/v1/payments/does-not-exist", null).getStatusCode())
                    .isNotEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(get(rest, "/actuator/health", null).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get(rest, "/actuator/health/readiness", null).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("with a secret configured, introspection surfaces are gated too")
        void introspectionSurfacesGated() {
            assertThat(get(rest, "/actuator/metrics", null).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(get(rest, "/v3/api-docs", null).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(get(rest, "/v3/api-docs", SECRET).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    /**
     * Fail-closed: turning a dev surface on without a secret must stop the service, never serve the
     * surface anonymously. Asserted on the config class in isolation so the failure is unambiguous.
     */
    @Nested
    @DisplayName("fail-closed configuration")
    class FailClosed {

        private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        ServletWebServerFactoryAutoConfiguration.class))
                .withUserConfiguration(SandboxSurfaceInternalAuthConfig.class);

        @Test
        @DisplayName("sandbox runner on + no secret → context refuses to start")
        void sandboxOnWithoutSecretFailsClosed() {
            runner.withPropertyValues("gmepay.sandbox.e2e.enabled=true")
                    .run(ctx -> assertThat(ctx).hasFailed()
                            .getFailure()
                            .hasMessageContaining("refuses to start")
                            .hasMessageContaining("GMEPAY_INTERNAL_AUTH_SECRET"));
        }

        @Test
        @DisplayName("sandbox runner on + blank secret → context refuses to start")
        void blankSecretFailsClosed() {
            runner.withPropertyValues("gmepay.sandbox.e2e.enabled=true",
                            "gmepay.internal-auth.secret=   ")
                    .run(ctx -> assertThat(ctx).hasFailed());
        }

        @Test
        @DisplayName("/__data dev tool on + no secret → context refuses to start")
        void devtoolsOnWithoutSecretFailsClosed() {
            runner.withPropertyValues("gmepay.devtools.enabled=true")
                    .run(ctx -> assertThat(ctx).hasFailed()
                            .getFailure()
                            .hasMessageContaining("refuses to start"));
        }

        @Test
        @DisplayName("both surfaces off + no secret → starts, and the filter still guards /v1/balance")
        void allOffNeedsNoSecret() {
            runner.run(ctx -> {
                // A bare payment-executor must still boot and serve payments without a secret —
                // making one mandatory for every boot is a deployment change beyond this fix.
                assertThat(ctx).hasNotFailed();
                @SuppressWarnings("unchecked")
                org.springframework.boot.web.servlet.FilterRegistrationBean<
                        com.gme.pay.internalauth.InternalAuthFilter> reg =
                        ctx.getBean("sandboxSurfaceInternalAuthFilter",
                                org.springframework.boot.web.servlet.FilterRegistrationBean.class);
                // T0-2: since /v1/balance is gated unconditionally there is always something to
                // gate, so the registration is enabled even in the default posture. With a blank
                // secret the filter can match no credential at all, so the balance inquiry answers
                // 401 to everyone — fail-closed rather than leaking any partner's float.
                assertThat(reg.isEnabled())
                        .as("the filter must run so /v1/balance is never anonymous")
                        .isTrue();
                assertThat(refusedByFilter(reg.getFilter(), "/v1/balance", null))
                        .as("blank secret ⇒ no credential can match ⇒ every balance read is refused")
                        .isTrue();
                assertThat(refusedByFilter(reg.getFilter(), "/v1/balance", "anything"))
                        .isTrue();
                // ...and it must still not touch the partner-facing pay surface.
                assertThat(refusedByFilter(reg.getFilter(), "/v1/payments/abc", null)).isFalse();
            });
        }

        @Test
        @DisplayName("surface on + secret present → starts with the gate armed")
        void onWithSecretArmsTheGate() {
            runner.withPropertyValues("gmepay.sandbox.e2e.enabled=true",
                            "gmepay.internal-auth.secret=" + SECRET)
                    .run(ctx -> {
                        assertThat(ctx).hasNotFailed();
                        assertThat(ctx.getBean("sandboxSurfaceInternalAuthFilter",
                                org.springframework.boot.web.servlet.FilterRegistrationBean.class)
                                .isEnabled()).isTrue();
                    });
        }

        @Test
        @DisplayName("secret alone (no dev surface) → starts with the gate armed over introspection")
        void secretAloneArmsIntrospectionGate() {
            runner.withPropertyValues("gmepay.internal-auth.secret=" + SECRET).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBean("sandboxSurfaceInternalAuthFilter",
                        org.springframework.boot.web.servlet.FilterRegistrationBean.class)
                        .isEnabled()).isTrue();
            });
        }
    }

    /**
     * The real deployment posture: no dev surface, but the internal secret IS set (payment-executor
     * needs it to call the gated prefunding service). Introspection must be closed and the
     * production pay surface must be untouched.
     */
    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = "gmepay.internal-auth.secret=" + SECRET)
    @DisplayName("production posture (secret set, no dev surface)")
    class ProductionPosture {

        @Autowired private TestRestTemplate rest;

        @BeforeEach
        void useJdkClient() {
            rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
        }

        @Test
        @DisplayName("the sandbox payment runner is still absent (404), secret or not")
        void sandboxStillAbsent() {
            assertThat(get(rest, "/v1/sandbox/e2e/options", SECRET).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(get(rest, "/__data/tables", SECRET).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("introspection is gated; container probes are not")
        void introspectionGatedProbesOpen() {
            assertThat(get(rest, "/actuator/metrics", null).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(get(rest, "/v3/api-docs", null).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(get(rest, "/actuator/health", null).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get(rest, "/actuator/health/liveness", null).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("the partner-facing pay surface is NOT swept into the internal gate")
        void paySurfaceUntouched() {
            // Regression guard: an over-broad pattern here would 401 every real payment.
            assertThat(get(rest, "/v1/payments/does-not-exist", null).getStatusCode())
                    .isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("GET /v1/balance requires the internal token (T0-2: was a header-swap IDOR)")
        void balanceInquiryIsGated() {
            // This assertion is the inversion of an earlier one that pinned /v1/balance as NOT gated.
            // The endpoint reads any partner's prefunding float and deduction history and identified
            // the partner purely from caller-supplied headers with fail-open defaults
            // (X-Partner-Id=1, X-Partner-Type=OVERSEAS), so an anonymous GET returned partner 1's
            // balance and any other partner's was one header away. It is internal-only: the
            // api-gateway does not route /v1/balance at all and no caller exists in the repo.
            assertThat(get(rest, "/v1/balance", null).getStatusCode())
                    .as("anonymous balance inquiry must be refused")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(get(rest, "/v1/balance?include_history=true", null).getStatusCode())
                    .as("the deduction-history variant must be refused too")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(get(rest, "/v1/balance", SECRET + "-tampered").getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);

            // A trusted caller gets past the gate. It then hits the T0-2 tenancy rule — no
            // X-Partner-Code means 400, never a default partner — which is not a 401.
            assertThat(get(rest, "/v1/balance", SECRET).getStatusCode())
                    .as("a trusted internal caller must not be refused by the gate")
                    .isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
