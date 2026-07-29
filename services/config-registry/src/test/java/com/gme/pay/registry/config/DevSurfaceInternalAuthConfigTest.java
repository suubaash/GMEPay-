package com.gme.pay.registry.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.internalauth.InternalAuthFilter;
import com.gme.pay.internalauth.InternalAuthHeaders;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * T0-2 — the {@code /__data} catalogue dump is flag-gated <b>and</b> token-gated, and turning it on
 * without a secret is a startup failure.
 *
 * <p>{@code DevDataController} dumps every table in this service's schema — for config-registry that
 * is the partner catalogue and its credential metadata (partner records, commercial terms, bank
 * accounts, mTLS certificates, IP allowlists, webhook subscriptions, API-key rows). It was already
 * off by default, but it had <b>no authentication at all when on</b>.
 *
 * <p>Exercised through the real {@link InternalAuthFilter} that
 * {@link DevSurfaceInternalAuthConfig} registers, so a pass is about the filter chain a request
 * actually traverses rather than about configuration values.
 */
class DevSurfaceInternalAuthConfigTest {

    private static final String SECRET = "fixture-token-not-a-deployment-secret";

    private final DevSurfaceInternalAuthConfig config = new DevSurfaceInternalAuthConfig();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("devtools on + no secret → refuses to start (never an anonymous catalogue dump)")
    void devSurfaceWithoutSecretFailsClosed(String secret) {
        assertThatThrownBy(() -> config.devSurfaceInternalAuthFilter(true, secret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refuses to start")
                .hasMessageContaining("/__data/**")
                .hasMessageContaining("GMEPAY_INTERNAL_AUTH_SECRET");
    }

    @Test
    @DisplayName("production posture (devtools off, no secret) → filter registered but disabled")
    void defaultPostureRegistersNothing() {
        assertThat(config.devSurfaceInternalAuthFilter(false, "").isEnabled()).isFalse();
    }

    @Test
    @DisplayName("secret present, devtools off → introspection is gated, /v1/partners is not")
    void secretAloneGatesOnlyIntrospection() throws Exception {
        InternalAuthFilter filter = config.devSurfaceInternalAuthFilter(false, SECRET).getFilter();

        assertThat(refuses(filter, "/actuator/metrics")).isTrue();
        assertThat(refuses(filter, "/v3/api-docs")).isTrue();
        // The operator surface must NEVER be swept in — the ops BFF and gateway front it.
        assertThat(refuses(filter, "/v1/partners/GMEREMIT")).isFalse();
        assertThat(refuses(filter, "/v1/schemes")).isFalse();
        assertThat(refuses(filter, "/actuator/health")).isFalse();
    }

    @Test
    @DisplayName("devtools on + secret → /__data needs the token; a correct token passes through")
    void devSurfaceIsTokenGated() throws Exception {
        FilterRegistrationBean<InternalAuthFilter> reg =
                config.devSurfaceInternalAuthFilter(true, SECRET);
        assertThat(reg.isEnabled()).isTrue();
        InternalAuthFilter filter = reg.getFilter();

        assertThat(refuses(filter, "/__data/tables")).isTrue();
        assertThat(refuses(filter, "/__data/tables/partners")).isTrue();
        assertThat(refusesWithToken(filter, "/__data/tables", SECRET + "-tampered")).isTrue();
        assertThat(refusesWithToken(filter, "/__data/tables", SECRET)).isFalse();
    }

    @Test
    @DisplayName("the gated pattern list never contains the operator surface")
    void patternListExcludesOperatorSurface() {
        assertThat(DevSurfaceInternalAuthConfig.DEVTOOLS_PATTERN).isEqualTo("/__data/**");
        assertThat(DevSurfaceInternalAuthConfig.INTROSPECTION_PATTERNS)
                .doesNotContain("/v1/partners/**", "/**")
                .allSatisfy(p -> assertThat(p).doesNotStartWith("/v1/"));
    }

    private static boolean refuses(InternalAuthFilter filter, String uri) throws Exception {
        return refusesWithToken(filter, uri, null);
    }

    /** True when the filter answered 401 itself instead of delegating down the chain. */
    private static boolean refusesWithToken(InternalAuthFilter filter, String uri, String token)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        if (token != null) {
            request.addHeader(InternalAuthHeaders.INTERNAL_TOKEN, token);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        boolean delegated = !Mockito.mockingDetails(chain).getInvocations().isEmpty();
        return !delegated && response.getStatus() == 401;
    }
}
