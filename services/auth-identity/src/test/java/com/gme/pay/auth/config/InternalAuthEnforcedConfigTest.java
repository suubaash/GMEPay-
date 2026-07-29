package com.gme.pay.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.auth.config.InternalAuthEnforcedConfig.InternalAuthGateAssertion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

/**
 * T0-2 — <b>fail-closed proof</b> for the internal-auth gate on auth-identity.
 *
 * <p>The gate is only as good as the guarantee that it is switched on in the environment that
 * actually runs, and before T0-2 that guarantee did not exist: the flag was
 * {@code ${GMEPAY_INTERNAL_AUTH_ENABLED:false}}, so a deployment that forgot one env var published
 * anonymous JWT minting and anonymous partner API-key issuance. Every way of ending up with an
 * un-gated identity service must now stop the service from booting rather than degrade quietly:
 *
 * <ul>
 *   <li>secret absent / blank (the deployment forgot {@code GMEPAY_INTERNAL_AUTH_SECRET} — the
 *       common case, since there is intentionally no default in the repo)</li>
 *   <li>{@code gmepay.internal-auth.enabled=false} (a stray env var would un-register the filter)</li>
 *   <li>{@code path-patterns} narrowed so an internal surface falls outside the gate</li>
 * </ul>
 *
 * <p>Asserted directly against {@link InternalAuthGateAssertion} — the bean's
 * {@code afterPropertiesSet} is what Spring calls during refresh, so throwing here is exactly a
 * failed startup — plus one assertion over the <em>shipped</em> {@code application.yml} so that
 * re-introducing the env-defeatable default is itself a test failure. That the armed configuration
 * boots a real context is covered by
 * {@code com.gme.pay.auth.internalauth.InternalAuthLiveGateTest}.
 */
class InternalAuthEnforcedConfigTest {

    private static final List<String> PROD_PATTERNS =
            List.of("/internal/**", "/v1/rbac/**", "/v1/approvals/**", "/actuator/metrics/**");

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("absent/blank secret → refuses to boot (never mints tokens anonymously)")
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
    @DisplayName("any internal surface dropped from path-patterns → refuses to boot")
    void narrowedPatternsFailClosed() {
        assertThatThrownBy(() -> new InternalAuthGateAssertion(
                true, "a-secret", List.of("/v1/rbac/**", "/v1/approvals/**")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/internal/**");

        assertThatThrownBy(() -> new InternalAuthGateAssertion(
                true, "a-secret", List.of("/internal/**", "/v1/approvals/**")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/v1/rbac/**");

        assertThatThrownBy(() -> new InternalAuthGateAssertion(
                true, "a-secret", List.of("/internal/**", "/v1/rbac/**")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/v1/approvals/**");

        assertThatThrownBy(() -> new InternalAuthGateAssertion(true, "a-secret", null)
                .afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("armed gate (enabled + secret + every required pattern) → boots")
    void armedGateBoots() {
        assertThatCode(() ->
                new InternalAuthGateAssertion(true, "a-secret", PROD_PATTERNS).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the required-pattern list still covers all three internal surfaces")
    void requiredPatternsCoverEverySurface() {
        assertThat(InternalAuthEnforcedConfig.REQUIRED_PATTERNS)
                .containsExactlyInAnyOrder("/internal/**", "/v1/rbac/**", "/v1/approvals/**");
    }

    @Test
    @DisplayName("the SHIPPED application.yml pins the gate on and defaults the secret to nothing")
    void shippedConfigDoesNotDefaultTheGateOff() throws Exception {
        // Reads the packaged config, so re-introducing GMEPAY_INTERNAL_AUTH_ENABLED:false — the exact
        // T0-2 regression — fails here even if every other test still passes.
        String yaml;
        try (var in = new ClassPathResource("application.yml").getInputStream()) {
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // Strip comment lines so the explanatory prose (which names the old env var) is not matched.
        String config = yaml.lines()
                .filter(l -> !l.stripLeading().startsWith("#"))
                .collect(Collectors.joining("\n"));

        assertThat(config)
                .as("application.yml must not make the gate env-defeatable")
                .doesNotContain("GMEPAY_INTERNAL_AUTH_ENABLED")
                .as("the gate must be pinned on")
                .contains("enabled: true")
                .as("the secret must have no in-repo default")
                .contains("secret: ${GMEPAY_INTERNAL_AUTH_SECRET:}");

        for (String required : InternalAuthEnforcedConfig.REQUIRED_PATTERNS) {
            assertThat(config).as("application.yml must gate %s", required).contains(required);
        }
    }
}
