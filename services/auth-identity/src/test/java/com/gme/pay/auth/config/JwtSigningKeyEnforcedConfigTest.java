package com.gme.pay.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.auth.config.JwtSigningKeyEnforcedConfig.JwtSigningKeyAssertion;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

/**
 * T0-6 — <b>fail-closed proof</b> for the HS256 JWT signing key.
 *
 * <p>Before this, {@code gme.auth.jwt.signing-secret} defaulted to the literal
 * {@code changeme-at-least-32-chars-long!!} in <em>two</em> places (the shipped
 * {@code application.yml} and the {@code @Value} default in {@link AuthConfig}) while
 * {@code GME_AUTH_JWT_SIGNING_SECRET} was set in no deployment file at all. HS256 is symmetric, so
 * every environment was minting platform capability tokens with a key an attacker can read out of
 * the repository — forgeable tokens, not merely a weak key. The default was even sized to clear the
 * 32-character HS256 expectation, so it failed <em>open</em> and silently.
 *
 * <p>Asserted directly against {@link JwtSigningKeyAssertion} — {@code afterPropertiesSet} is what
 * Spring calls during context refresh, so throwing here is exactly a failed startup — plus one
 * assertion over the <em>shipped</em> {@code application.yml} so that re-introducing the default is
 * itself a test failure even if every other test still passes.
 */
class JwtSigningKeyEnforcedConfigTest {

    /** A key shaped like a real one: long enough, no placeholder words, never published. */
    private static final String GOOD_KEY = "b3f1c0a94d7e2856bb0f4a1c9d8e7f60";

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("absent/blank key → refuses to boot (never signs tokens with nothing)")
    void blankKeyFailsClosed(String secret) {
        assertThatThrownBy(() -> new JwtSigningKeyAssertion(secret).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refuses to start")
                .hasMessageContaining("GME_AUTH_JWT_SIGNING_SECRET");
    }

    @Test
    @DisplayName("the exact published literal → refuses to boot (the T0-6 regression itself)")
    void publishedKeyFailsClosed() {
        assertThatThrownBy(() ->
                new JwtSigningKeyAssertion("changeme-at-least-32-chars-long!!").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PUBLISHED");
    }

    @Test
    @DisplayName("every key ever published in this repo is rejected")
    void everyPublishedKeyFailsClosed() {
        assertThat(JwtSigningKeyEnforcedConfig.PUBLISHED_KEYS).isNotEmpty();
        for (String published : JwtSigningKeyEnforcedConfig.PUBLISHED_KEYS) {
            assertThatThrownBy(() -> new JwtSigningKeyAssertion(published).afterPropertiesSet())
                    .as("published key %s must never authenticate anything again", published)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CHANGE_ME_AUTH_JWT_SIGNING_SECRET_PADDED_OUT",
            "REPLACE_FROM_SECRETS_MANAGER_PADDED_TO_32_CHARS",
            "REPLACE_FROM_KEY_VAULT_PADDED_TO_32_CHARS",
            "your-secret-key-goes-here-padded-out-to-32",
            "TODO-set-a-real-signing-key-here-please"})
    @DisplayName("an unsubstituted deployment placeholder → refuses to boot")
    void placeholderFailsClosed(String placeholder) {
        assertThatThrownBy(() -> new JwtSigningKeyAssertion(placeholder).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    @DisplayName("a key shorter than the HS256 floor → refuses to boot")
    void shortKeyFailsClosed() {
        String thirtyOne = "a".repeat(JwtSigningKeyEnforcedConfig.MIN_KEY_LENGTH - 1);
        assertThatThrownBy(() -> new JwtSigningKeyAssertion(thirtyOne).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HS256 requires at least");
    }

    @Test
    @DisplayName("a real operator-supplied key → boots")
    void goodKeyBoots() {
        assertThat(GOOD_KEY.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThanOrEqualTo(JwtSigningKeyEnforcedConfig.MIN_KEY_LENGTH);
        assertThatCode(() -> new JwtSigningKeyAssertion(GOOD_KEY).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the SHIPPED application.yml carries no signing-key default at all")
    void shippedConfigHasNoSigningKeyDefault() throws Exception {
        // Reads the packaged config, so re-introducing ${GME_AUTH_JWT_SIGNING_SECRET:<anything>} —
        // the exact T0-6 regression — fails here even if every other test still passes.
        String yaml;
        try (var in = new ClassPathResource("application.yml").getInputStream()) {
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // Strip comment lines so the explanatory prose (which names the old literal) is not matched.
        String config = yaml.lines()
                .filter(l -> !l.stripLeading().startsWith("#"))
                .collect(Collectors.joining("\n"));

        assertThat(config)
                .as("the signing key must have no in-repo default")
                .contains("signing-secret: ${GME_AUTH_JWT_SIGNING_SECRET:}");

        for (String published : JwtSigningKeyEnforcedConfig.PUBLISHED_KEYS) {
            assertThat(config)
                    .as("application.yml must not ship the published key %s", published)
                    .doesNotContain(published);
        }
        assertThat(config)
                .as("no placeholder-shaped default may creep back in")
                .doesNotContain("changeme");
    }
}
