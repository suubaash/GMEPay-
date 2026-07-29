package com.gme.pay.scheme.zeropay.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.scheme.zeropay.config.InternalAuthEnforcedConfig.InternalAuthGateAssertion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

/**
 * T0-2 — <b>fail-closed proof</b> for the internal-auth gate on scheme-adapter-zeropay.
 *
 * <p>The gate is only as good as the guarantee that it is switched on in the environment that
 * actually runs. Every way of ending up with an un-gated scheme-submission API must stop the service
 * from booting, not degrade quietly:
 *
 * <ul>
 *   <li>secret absent / blank (the deployment forgot {@code GMEPAY_INTERNAL_AUTH_SECRET} — the
 *       common case, since there is intentionally no default in the repo)</li>
 *   <li>{@code gmepay.internal-auth.enabled=false} (a stray env var would un-register the filter)</li>
 *   <li>{@code path-patterns} narrowed so {@code /internal/**} falls outside the gate</li>
 * </ul>
 *
 * <p>Asserted directly against {@link InternalAuthGateAssertion} — the bean's
 * {@code afterPropertiesSet} is what Spring calls during refresh, so throwing here is exactly a
 * failed startup — plus one assertion over the <em>shipped</em> {@code application.properties}, so
 * removing the gate config is itself a test failure. That the armed configuration boots a real
 * context is covered by {@code com.gme.pay.scheme.zeropay.api.InternalAuthGateTest}.
 */
class InternalAuthEnforcedConfigTest {

    private static final List<String> PROD_PATTERNS =
            List.of("/internal/**", "/__data/**", "/actuator/metrics/**");

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("absent/blank secret → refuses to boot (never submits to the scheme anonymously)")
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
    @DisplayName("/internal/** dropped from path-patterns → refuses to boot")
    void narrowedPatternsFailClosed() {
        assertThatThrownBy(() -> new InternalAuthGateAssertion(
                true, "a-secret", List.of("/__data/**")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/internal/**");

        assertThatThrownBy(() -> new InternalAuthGateAssertion(true, "a-secret", null)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("armed gate (enabled + secret + the required pattern) → boots")
    void armedGateBoots() {
        assertThatCode(() ->
                new InternalAuthGateAssertion(true, "a-secret", PROD_PATTERNS).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the SHIPPED application.properties arms the gate with no in-repo secret")
    void shippedConfigArmsTheGate() throws Exception {
        String config;
        try (var in = new ClassPathResource("application.properties").getInputStream()) {
            config = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String settings = config.lines()
                .filter(l -> !l.stripLeading().startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(settings)
                .contains("gmepay.internal-auth.enabled=true")
                .contains("gmepay.internal-auth.secret=${GMEPAY_INTERNAL_AUTH_SECRET:}");
        for (String required : InternalAuthEnforcedConfig.REQUIRED_PATTERNS) {
            assertThat(settings).as("must gate %s", required).contains(required);
        }
    }
}
