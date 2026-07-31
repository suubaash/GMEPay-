package com.gme.pay.auth.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gme.pay.auth.config.JwtSigningKeyEnforcedConfig.JwtSigningKeyAssertion;
import com.gme.pay.auth.domain.JwtKeySet;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * T0-6 (rotation half) — <b>fail-closed proof for the key SET</b>.
 *
 * <p>{@link JwtSigningKeyEnforcedConfigTest} pins the original single-key guarantees. This pins the
 * two things the rotation build adds, and the fact that adding them did not weaken the first set:
 *
 * <ol>
 *   <li><b>Every key in the set clears the same bar.</b> A previously active key is live signing
 *       material for as long as it is accepted, so a published / placeholder-shaped / too-short
 *       value in {@code gme.auth.jwt.previous-keys} must fail the boot exactly as it does in
 *       {@code gme.auth.jwt.signing-secret}. Otherwise "rotation" would be a hole in the T0-6
 *       gate: an operator could park {@code changeme-at-least-32-chars-long!!} in the accepted
 *       list and the service would happily verify tokens forged with it.</li>
 *   <li><b>Retiring a key whose tokens can still be live is refused.</b> A rotation that promotes a
 *       new key and drops the outgoing one in the same step invalidates every live token; the
 *       service refuses to start rather than let the operator discover that from a wave of 401s.
 *       It is a refusal and not a warning because the alternative — a warning nobody reads and a
 *       fleet-wide session loss — is the exact failure the overlap window exists to prevent.</li>
 * </ol>
 *
 * <p>Asserted directly against {@link JwtSigningKeyAssertion}, whose {@code afterPropertiesSet} is
 * what Spring calls during context refresh, so throwing here is exactly a failed startup. The
 * time-dependent rules go through the package-private {@code assertKeySet(Instant)} so they are
 * deterministic rather than wall-clock-dependent.
 */
class JwtKeySetEnforcedConfigTest {

    private static final String ACTIVE = "b3f1c0a94d7e2856bb0f4a1c9d8e7f60";
    private static final String OLD    = "5c8ea31f60b7429dae10cf7538d92b4e";
    private static final Instant NOW   = Instant.parse("2026-07-28T12:00:00Z");
    private static final long MAX_TTL  = 3600L;

    private static JwtSigningKeyAssertion assertion(String active, String previous) {
        return new JwtSigningKeyAssertion(active, previous, "", false, MAX_TTL);
    }

    // ── 1. every key in the set keeps the T0-6 guarantees ─────────────────────

    @Test
    @DisplayName("a good active key with a dated predecessor boots, and yields both keys")
    void validKeySetBoots() {
        JwtKeySet set = assertion(ACTIVE, OLD + "@2026-07-28T09:00:00Z").assertKeySet(NOW);

        assertThat(set.activeKey().kid()).isEqualTo(JwtKeySet.kidFor(ACTIVE));
        assertThat(set.previousKeys()).singleElement()
                .satisfies(k -> assertThat(k.kid()).isEqualTo(JwtKeySet.kidFor(OLD)));
    }

    @Test
    @DisplayName("a PUBLISHED key in the accepted set refuses to boot, exactly as in the active slot")
    void publishedPreviousKeyFailsClosed() {
        for (String published : JwtSigningKeyEnforcedConfig.PUBLISHED_KEYS) {
            assertThatThrownBy(() ->
                    assertion(ACTIVE, published + "@2026-07-28T09:00:00Z").assertKeySet(NOW))
                    .as("published key %s must not be smuggled in as a 'previous' key", published)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("refuses to start")
                    .hasMessageContaining("PUBLISHED")
                    .hasMessageContaining("previous-keys");
        }
    }

    @Test
    @DisplayName("a placeholder in the accepted set refuses to boot")
    void placeholderPreviousKeyFailsClosed() {
        assertThatThrownBy(() -> assertion(ACTIVE,
                "CHANGE_ME_PREVIOUS_JWT_KEY_PADDED_OUT_32@2026-07-28T09:00:00Z").assertKeySet(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("placeholder");
    }

    @Test
    @DisplayName("a too-short key in the accepted set refuses to boot")
    void shortPreviousKeyFailsClosed() {
        String tooShort = "a".repeat(JwtSigningKeyEnforcedConfig.MIN_KEY_LENGTH - 1);
        assertThatThrownBy(() ->
                assertion(ACTIVE, tooShort + "@2026-07-28T09:00:00Z").assertKeySet(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HS256 requires at least");
    }

    @Test
    @DisplayName("a set with NO active key refuses to boot even when it holds accepted keys")
    void keySetWithoutAnActiveKeyFailsClosed() {
        // Verification-only keys cannot substitute for the signing key: the service would accept
        // tokens it can no longer mint, which is a service that cannot do its job while looking
        // healthy.
        assertThatThrownBy(() -> assertion("", OLD + "@2026-07-28T09:00:00Z").assertKeySet(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refuses to start")
                .hasMessageContaining("no ACTIVE key");
    }

    @Test
    @DisplayName("the same secret as both active and previous refuses to boot")
    void duplicateKeyFailsClosed() {
        assertThatThrownBy(() ->
                assertion(ACTIVE, ACTIVE + "@2026-07-28T09:00:00Z").assertKeySet(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    @DisplayName("a malformed previous-keys entry refuses to boot with the guard's own message")
    void malformedPreviousKeysFailsClosed() {
        assertThatThrownBy(() -> assertion(ACTIVE, OLD).assertKeySet(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refuses to start")
                .hasMessageContaining("missing its '@<demoted-at>' timestamp");
    }

    @Test
    @DisplayName("a demotion timestamp in the future refuses to boot")
    void futureDemotionFailsClosed() {
        assertThatThrownBy(() ->
                assertion(ACTIVE, OLD + "@2027-01-01T00:00:00Z").assertKeySet(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in the FUTURE");
    }

    @Test
    @DisplayName("the single-key form still behaves exactly as before (the gate was extended, not relaxed)")
    void singleKeyFormUnchanged() {
        assertThatCode(() -> new JwtSigningKeyAssertion(ACTIVE).afterPropertiesSet())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new JwtSigningKeyAssertion("").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
        assertThat(ACTIVE.getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThanOrEqualTo(JwtSigningKeyEnforcedConfig.MIN_KEY_LENGTH);
    }

    // ── 2. premature retirement ───────────────────────────────────────────────

    @Test
    @DisplayName("retiring the outgoing key inside its overlap window is REFUSED")
    void prematureRetirementIsRefused() {
        // Active key promoted 10 minutes ago (< the 1 h max TTL) and nothing accepted alongside it:
        // every token minted before the switch is dead.
        String activatedAt = NOW.minus(Duration.ofMinutes(10)).toString();

        assertThatThrownBy(() ->
                new JwtSigningKeyAssertion(ACTIVE, "", activatedAt, false, MAX_TTL).assertKeySet(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refuses to start")
                .hasMessageContaining("HARD CUTOVER")
                .hasMessageContaining("GME_AUTH_JWT_PREVIOUS_KEYS")
                .hasMessageContaining("GME_AUTH_JWT_ALLOW_HARD_CUTOVER");
    }

    @Test
    @DisplayName("the same rotation WITH the outgoing key kept accepted boots — that is the whole point")
    void gracefulRotationBoots() {
        String activatedAt = NOW.minus(Duration.ofMinutes(10)).toString();

        assertThatCode(() -> new JwtSigningKeyAssertion(
                ACTIVE, OLD + "@" + activatedAt, activatedAt, false, MAX_TTL).assertKeySet(NOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a declared hard cutover boots — first deployment, or a compromise response")
    void declaredHardCutoverBoots() {
        String activatedAt = NOW.minus(Duration.ofMinutes(10)).toString();

        assertThatCode(() -> new JwtSigningKeyAssertion(ACTIVE, "", activatedAt, true, MAX_TTL)
                .assertKeySet(NOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("once the overlap window has passed, an empty accepted set is normal steady state")
    void steadyStateAfterTheWindowBoots() {
        String activatedAt = NOW.minus(Duration.ofDays(30)).toString();

        assertThatCode(() -> new JwtSigningKeyAssertion(ACTIVE, "", activatedAt, false, MAX_TTL)
                .assertKeySet(NOW))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an unparseable or future activation instant refuses to boot")
    void badActivationInstantFailsClosed() {
        assertThatThrownBy(() ->
                new JwtSigningKeyAssertion(ACTIVE, "", "yesterday", false, MAX_TTL).assertKeySet(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ISO-8601");

        assertThatThrownBy(() -> new JwtSigningKeyAssertion(
                ACTIVE, "", NOW.plus(Duration.ofDays(1)).toString(), false, MAX_TTL).assertKeySet(NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("in the FUTURE");
    }

    @Test
    @DisplayName("an overdue key is WARNED about, not refused — a housekeeping omission is not an outage")
    void overdueKeyIsWarnedNotRefused() {
        // Demoted five hours ago with a one-hour max TTL: nothing it signed can still be live, so
        // it is pure excess signing material. Refusing here would take the service down over a
        // forgotten config line, and an operator may be holding the key deliberately mid-incident.
        String demotedAt = NOW.minus(Duration.ofHours(5)).toString();
        JwtKeySet set = assertion(ACTIVE, OLD + "@" + demotedAt).assertKeySet(NOW);

        JwtKeySet.KeyStatus status = set.report(Duration.ofSeconds(MAX_TTL), NOW).get(1);
        assertThat(status.overdueForRemoval()).isTrue();
        assertThat(status.safeToRemoveNow()).isTrue();
    }

    // ── 3. the SHIPPED config ─────────────────────────────────────────────────

    @Test
    @DisplayName("the SHIPPED application.yml wires the rotation properties with no working defaults")
    void shippedConfigWiresRotation() throws Exception {
        String yaml;
        try (var in = new ClassPathResource("application.yml").getInputStream()) {
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String config = yaml.lines()
                .filter(l -> !l.stripLeading().startsWith("#"))
                .collect(Collectors.joining("\n"));

        // Reading the packaged config, not a fixture: if a future edit drops one of these the
        // property silently disappears and the rotation machinery is unreachable from a manifest.
        assertThat(config)
                .as("the accepted-key list must be wired and default to empty (no key, not a literal)")
                .contains("previous-keys: ${GME_AUTH_JWT_PREVIOUS_KEYS:}");
        assertThat(config)
                .as("the activation instant must be wired with no default, or the "
                    + "premature-retirement check can never fire")
                .contains("active-key-activated-at: ${GME_AUTH_JWT_ACTIVE_KEY_ACTIVATED_AT:}");
        assertThat(config)
                .as("hard cutover must default to FALSE — losing every live token is never the "
                    + "default behaviour")
                .contains("allow-hard-cutover: ${GME_AUTH_JWT_ALLOW_HARD_CUTOVER:false}");
        assertThat(config)
                .as("the active key still has no in-repo default")
                .contains("signing-secret: ${GME_AUTH_JWT_SIGNING_SECRET:}");

        for (String published : JwtSigningKeyEnforcedConfig.PUBLISHED_KEYS) {
            assertThat(config).doesNotContain(published);
        }
    }
}
