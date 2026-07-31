package com.gme.pay.txn.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.internalauth.InternalAuthFilter;
import com.gme.pay.internalauth.InternalAuthHeaders;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * T0-2 — the {@code /__data} ledger dump is flag-gated <b>and</b> token-gated, and turning it on
 * without a secret is a startup failure.
 *
 * <p>{@code DevDataController} dumps every table in this service's schema — for transaction-mgmt that
 * is the transaction ledger. It was already off by default, but it had <b>no authentication at all
 * when on</b>, which is precisely the local / tunnelled posture where the port is reachable by
 * someone else.
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
    @DisplayName("devtools on + no secret → refuses to start (never an anonymous ledger dump)")
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
        FilterRegistrationBean<InternalAuthFilter> reg = config.devSurfaceInternalAuthFilter(false, "");
        assertThat(reg.isEnabled())
                .as("no dev surface and no secret ⇒ nothing to gate, so the filter must not run")
                .isFalse();
    }

    @Test
    @DisplayName("secret present, devtools off → introspection is gated, /v1/transactions is not")
    void secretAloneGatesOnlyIntrospection() throws Exception {
        InternalAuthFilter filter = filterFrom(config.devSurfaceInternalAuthFilter(false, SECRET));

        assertThat(refuses(filter, "/actuator/metrics")).isTrue();
        assertThat(refuses(filter, "/v3/api-docs")).isTrue();
        // The partner/operator surface must NEVER be swept in — it is authenticated at the gateway.
        assertThat(refuses(filter, "/v1/transactions/123")).isFalse();
        assertThat(refuses(filter, "/actuator/health")).isFalse();
        assertThat(refuses(filter, "/actuator/health/readiness")).isFalse();
    }

    @Test
    @DisplayName("devtools on + secret → /__data needs the token; a correct token passes through")
    void devSurfaceIsTokenGated() throws Exception {
        FilterRegistrationBean<InternalAuthFilter> reg =
                config.devSurfaceInternalAuthFilter(true, SECRET);
        assertThat(reg.isEnabled()).isTrue();
        InternalAuthFilter filter = filterFrom(reg);

        assertThat(refuses(filter, "/__data/tables")).isTrue();
        assertThat(refuses(filter, "/__data/tables/transactions")).isTrue();
        assertThat(refusesWithToken(filter, "/__data/tables", SECRET + "-tampered")).isTrue();
        assertThat(refusesWithToken(filter, "/__data/tables", SECRET)).isFalse();
    }

    @Test
    @DisplayName("the gated pattern list never contains the public surface")
    void patternListExcludesPublicSurface() {
        assertThat(DevSurfaceInternalAuthConfig.DEVTOOLS_PATTERN).isEqualTo("/__data/**");
        assertThat(DevSurfaceInternalAuthConfig.INTROSPECTION_PATTERNS)
                .doesNotContain("/v1/transactions/**", "/**")
                .allSatisfy(p -> assertThat(p).doesNotStartWith("/v1/"));
    }

    private static InternalAuthFilter filterFrom(FilterRegistrationBean<InternalAuthFilter> reg) {
        return reg.getFilter();
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
        boolean delegated = Mockito.mockingDetails(chain).getInvocations().size() > 0;
        return !delegated && response.getStatus() == 401;
    }

    /** Guards against the helper silently reporting "not refused" because the chain was real. */
    @Test
    @DisplayName("helper sanity: a pass-through really does reach the chain")
    void helperDetectsPassThrough() throws Exception {
        InternalAuthFilter filter = new InternalAuthFilter(SECRET, List.of("/__data/**"));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/transactions/1");
        request.setRequestURI("/v1/transactions/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        assertThat(chain.getRequest()).as("un-gated path must reach the chain").isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
