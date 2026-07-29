package com.gme.pay.prefunding.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.prefunding.config.InternalAuthEnforcedConfig.InternalAuthGateAssertion;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * T0-5 / CISO#6 — <b>fail-closed proof</b> for the internal-auth gate on prefunding.
 *
 * <p>The gate is only as good as the guarantee that it is switched on in the environment that
 * actually runs. Every way of ending up with an un-gated money API must therefore stop the service
 * from booting, not degrade quietly:
 *
 * <ul>
 *   <li>secret absent / blank (the deployment forgot {@code GMEPAY_INTERNAL_AUTH_SECRET} — the
 *       common case, since there is intentionally no default in the repo)</li>
 *   <li>{@code gmepay.internal-auth.enabled=false} (a stray env var would un-register the filter)</li>
 *   <li>{@code path-patterns} narrowed so the money surface falls outside the gate</li>
 * </ul>
 *
 * <p>Asserted directly against {@link InternalAuthGateAssertion} — the bean's
 * {@code afterPropertiesSet} is what Spring calls during refresh, so throwing here is exactly a
 * failed startup.
 */
class InternalAuthEnforcedConfigTest {

    private static final List<String> PROD_PATTERNS =
            List.of("/internal/**", "/v1/prefunding/**", "/actuator/metrics/**");

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("absent/blank secret → refuses to boot (never serves the balance API anonymously)")
    void blankSecretFailsClosed(String secret) {
        assertThatThrownBy(() ->
                new InternalAuthGateAssertion(true, secret, PROD_PATTERNS).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refuses to start")
                .hasMessageContaining("GMEPAY_INTERNAL_AUTH_SECRET");
    }

    @Test
    @DisplayName("gate switched off → refuses to boot even though a secret is present")
    void disabledGateFailsClosed() {
        assertThatThrownBy(() ->
                new InternalAuthGateAssertion(false, "a-secret", PROD_PATTERNS).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gmepay.internal-auth.enabled must be true");
    }

    @Test
    @DisplayName("both missing → still refuses to boot (the enabled=false + no-secret combination)")
    void disabledAndSecretlessFailsClosed() {
        assertThatThrownBy(() ->
                new InternalAuthGateAssertion(false, "", List.of()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("money surface dropped from path-patterns → refuses to boot")
    void narrowedPatternsFailClosed() {
        assertThatThrownBy(() -> new InternalAuthGateAssertion(
                true, "a-secret", List.of("/internal/**")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/v1/prefunding/**");

        assertThatThrownBy(() -> new InternalAuthGateAssertion(
                true, "a-secret", List.of("/v1/prefunding/**")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/internal/**");

        assertThatThrownBy(() -> new InternalAuthGateAssertion(true, "a-secret", null)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("armed gate (enabled + secret + both required patterns) → boots")
    void armedGateBoots() {
        assertThatCode(() ->
                new InternalAuthGateAssertion(true, "a-secret", PROD_PATTERNS).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the required-pattern list still covers both money surfaces")
    void requiredPatternsCoverBothSurfaces() {
        assertThat(InternalAuthEnforcedConfig.REQUIRED_PATTERNS)
                .containsExactlyInAnyOrder("/internal/**", "/v1/prefunding/**");
    }
}
