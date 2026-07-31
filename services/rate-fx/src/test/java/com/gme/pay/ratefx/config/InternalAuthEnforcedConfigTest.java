package com.gme.pay.ratefx.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.ratefx.config.InternalAuthEnforcedConfig.InternalAuthGateAssertion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

/**
 * T0-2 — <b>fail-closed proof</b> for the internal-auth gate over rate-fx's snapshot-admin surface.
 *
 * <p>Every way of ending up with an anonymous treasury-rate override must stop the service from
 * booting rather than degrade quietly: a blank secret, a switched-off gate, or a narrowed pattern
 * list. Asserted directly against {@link InternalAuthGateAssertion}, whose
 * {@code afterPropertiesSet} is what Spring calls during refresh — plus one assertion over the
 * <em>shipped</em> {@code application.properties} so removing the gate config is itself a failure.
 */
class InternalAuthEnforcedConfigTest {

    private static final List<String> PROD_PATTERNS = List.of(
            "/v1/rates/snapshots", "/v1/rates/snapshots/**", "/actuator/metrics/**");

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("absent/blank secret → refuses to boot (never accepts an anonymous rate override)")
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
    @DisplayName("snapshot path dropped from path-patterns → refuses to boot")
    void narrowedPatternsFailClosed() {
        assertThatThrownBy(() -> new InternalAuthGateAssertion(
                true, "a-secret", List.of("/actuator/metrics/**")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/v1/rates/snapshots");

        assertThatThrownBy(() -> new InternalAuthGateAssertion(true, "a-secret", null)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("armed gate (enabled + secret + both snapshot patterns) → boots")
    void armedGateBoots() {
        assertThatCode(() ->
                new InternalAuthGateAssertion(true, "a-secret", PROD_PATTERNS).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the SHIPPED application.properties arms the gate and leaves the public surface out")
    void shippedConfigArmsTheGateWithoutCatchingThePublicSurface() throws Exception {
        String config;
        try (var in = new ClassPathResource("application.properties").getInputStream()) {
            config = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String patternLine = config.lines()
                .filter(l -> l.startsWith("gmepay.internal-auth.path-patterns="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no gmepay.internal-auth.path-patterns line"));
        List<String> patterns = List.of(
                patternLine.substring(patternLine.indexOf('=') + 1).split(","));

        assertThat(patterns).containsAll(InternalAuthEnforcedConfig.REQUIRED_PATTERNS);
        // The public partner surface must never be swept in — that would decline partner traffic.
        assertThat(patterns)
                .doesNotContain("/v1/rates", "/v1/rates/**", "/v1/quotes/**", "/**");

        String settings = config.lines()
                .filter(l -> !l.stripLeading().startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(settings)
                .contains("gmepay.internal-auth.enabled=true")
                .contains("gmepay.internal-auth.secret=${GMEPAY_INTERNAL_AUTH_SECRET:}");
    }
}
