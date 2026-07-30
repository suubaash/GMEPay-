package com.gme.pay.auth.config;

import com.gme.pay.auth.domain.JwtKeySet;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fail-closed guard for the JWT signing key (T0-6 / CISO#4).
 *
 * <p>auth-identity mints the platform's capability tokens at
 * {@code POST /internal/auth/token/issue} and signs them HS256 with
 * {@code gme.auth.jwt.signing-secret}. HS256 is symmetric: the signing key <em>is</em> the
 * verification key, so anyone who knows it can mint a token for any subject with any claims.
 *
 * <p><b>What was broken.</b> The property defaulted to the literal
 * {@code changeme-at-least-32-chars-long!!} in two places — {@code application.yml} and the
 * {@code @Value} default in {@link AuthConfig} — and {@code GME_AUTH_JWT_SIGNING_SECRET} was set in
 * <em>zero</em> files: not {@code docker-compose.yml}, not any of the four Helm values files, not
 * {@code run-fleet.ps1}. The literal was also deliberately 33 characters, i.e. long enough to pass
 * the HS256 length expectation, so nothing anywhere complained. Every environment therefore signed
 * real tokens with a key published in a public-facing repository: <b>token forgery, not a weak
 * key</b>. That is the definition of a committed secret being the live default.
 *
 * <p><b>The fix.</b> There is no default any more, and a deployment that does not supply a usable
 * key <b>does not start</b>. Four rejection cases, all of them things that previously "worked":
 *
 * <ul>
 *   <li>absent or blank — the common case (the deployment forgot the env var);</li>
 *   <li>shorter than {@link #MIN_KEY_LENGTH} bytes — HS256's key is the HMAC key; a short key
 *       weakens the signature regardless of how the token looks;</li>
 *   <li>any {@link #PUBLISHED_KEYS published} value — the exact literals that have been in git
 *       history. A key in a repo is a key an attacker has; re-introducing one must be a startup
 *       failure and not a warning;</li>
 *   <li>obvious placeholder material ({@code changeme…}, {@code CHANGE_ME_…}, {@code REPLACE_…},
 *       {@code your-secret…}) — a Helm placeholder that reached a pod unsubstituted is a
 *       misconfiguration, not a key.</li>
 * </ul>
 *
 * <p>Startup failure is the correct outcome rather than a WARN: an auth-identity that will not boot
 * stops logins and credential issuance (loud, fail-closed), whereas one running on a published key
 * silently accepts forged ADMIN tokens.
 *
 * <p>Mirrors {@link InternalAuthEnforcedConfig} deliberately — same shape, same
 * {@code refuses to start} message prefix, same "assert as an {@link InitializingBean} so the
 * assertion runs during context refresh" mechanism.
 *
 * <h2>Extended for key rotation (T0-6, second half)</h2>
 *
 * <p>The service now takes a key <em>set</em> ({@link JwtKeySet}): one active key plus previously
 * active keys still accepted for verification. Every one of the guarantees above is applied to
 * <b>each key in the set</b>, not just the active one — a retired key is still live signing
 * material for as long as it is accepted, so a published or placeholder-shaped value in
 * {@code gme.auth.jwt.previous-keys} is exactly as dangerous as one in
 * {@code gme.auth.jwt.signing-secret} and fails the boot identically. The guard was extended, not
 * relaxed: the single-key path behaves exactly as before.
 *
 * <p>Four rotation-specific refusals are layered on top:
 *
 * <ul>
 *   <li><b>No active key</b> — a set that cannot sign cannot serve, whatever else it holds.</li>
 *   <li><b>An unparseable or undated previous key</b> — see
 *       {@link JwtKeySet#parsePreviousKeys(String)}. Without a demotion date there is no way to
 *       compute when the key is safe to remove, so the retirement step would be guesswork.</li>
 *   <li><b>A demotion date in the future</b> — the arithmetic that decides "these tokens can no
 *       longer be live" would run backwards.</li>
 *   <li><b>A hard cutover that was not declared as one</b> — if
 *       {@code gme.auth.jwt.active-key-activated-at} says the active key started signing less than
 *       one maximum token TTL ago and the accepted set is <em>empty</em>, then whatever key was
 *       signing before this one was dropped in the same step and every token minted before the
 *       switch is now dead. That is a legitimate action (a first deployment, or a compromise
 *       response) but never an accidental one, so it requires
 *       {@code gme.auth.jwt.allow-hard-cutover=true}.</li>
 * </ul>
 *
 * <p>And one loud warning: a key kept in the accepted set well past the point where its tokens
 * could still be live is an extra copy of live signing material sitting in a manifest for no
 * benefit. It is a WARN and not a refusal because refusing would take a service down over a
 * housekeeping omission, and because the operator may be holding the key deliberately during an
 * incident.
 *
 * <p><b>What premature retirement this cannot catch.</b> Detection here is bounded by what
 * configuration declares. A rotation that swaps {@code GME_AUTH_JWT_SIGNING_SECRET} and leaves
 * {@code GME_AUTH_JWT_ACTIVE_KEY_ACTIVATED_AT} stale is indistinguishable, to a stateless process,
 * from a service that has been running on the same key for months — the process has no memory of
 * which key it used yesterday. Closing that would need a persisted key registry (a table of
 * kid/first-seen/last-active), which is a schema change and a larger build; it is recorded as a
 * scoped residual rather than approximated here. What the process CAN see, it refuses.
 */
@Configuration
public class JwtSigningKeyEnforcedConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtSigningKeyEnforcedConfig.class);

    /**
     * Minimum signing-key length in bytes. HS256's HMAC block size is 64 bytes; 32 is the
     * conventional floor (and what the old comment claimed to require) — RFC 7518 §3.2 mandates a
     * key at least as long as the hash output.
     */
    public static final int MIN_KEY_LENGTH = 32;

    /**
     * Signing keys that have appeared in this repository. Any of them is compromised by
     * definition — the point of the T0-6 fix is that these can never authenticate anything again,
     * so re-adding one is a boot failure rather than a lint warning.
     */
    static final List<String> PUBLISHED_KEYS = List.of("changeme-at-least-32-chars-long!!");

    /** Lower-cased fragments that mean "nobody substituted the real value". */
    static final List<String> PLACEHOLDER_FRAGMENTS =
            List.of("changeme", "change_me", "change-me", "replace_", "replace-with", "replacefrom",
                    "your-secret", "yoursecret", "todo", "xxxxx");

    @Bean
    JwtSigningKeyAssertion jwtSigningKeyAssertion(
            @Value("${gme.auth.jwt.signing-secret:}") String signingSecret,
            @Value("${gme.auth.jwt.previous-keys:}") String previousKeysSpec,
            @Value("${gme.auth.jwt.active-key-activated-at:}") String activatedAtSpec,
            @Value("${gme.auth.jwt.allow-hard-cutover:false}") boolean allowHardCutover,
            @Value("${gme.auth.jwt.max-token-ttl-seconds:3600}") long maxTokenTtlSeconds) {
        return new JwtSigningKeyAssertion(signingSecret, previousKeysSpec, activatedAtSpec,
                                          allowHardCutover, maxTokenTtlSeconds);
    }

    /**
     * Builds the verified key set. Depends on the assertion bean so a configuration problem is
     * reported by the guard's message (which says what to do about it) rather than by whatever
     * {@link JwtKeySet} happens to throw first.
     */
    @Bean
    @org.springframework.context.annotation.DependsOn("jwtSigningKeyAssertion")
    JwtKeySet jwtKeySet(@Value("${gme.auth.jwt.signing-secret:}") String signingSecret,
                        @Value("${gme.auth.jwt.previous-keys:}") String previousKeysSpec) {
        return JwtKeySet.of(signingSecret.trim(), JwtKeySet.parsePreviousKeys(previousKeysSpec));
    }

    /**
     * Applies the T0-6 rules to one piece of key material, whatever its role in the set.
     *
     * @param secret   the raw configured value (already trimmed)
     * @param property the property it came from, so the message names the thing to fix
     * @param envVar   the environment variable that sets it
     */
    static void validateKeyMaterial(String secret, String property, String envVar) {
        if (PUBLISHED_KEYS.contains(secret)) {
            throw new IllegalStateException(failure(
                    property + " is a value that has been PUBLISHED in this repository and is "
                    + "therefore compromised. Generate a fresh key and supply it through " + envVar));
        }
        String lower = secret.toLowerCase(Locale.ROOT);
        for (String fragment : PLACEHOLDER_FRAGMENTS) {
            if (lower.contains(fragment)) {
                throw new IllegalStateException(failure(
                        property + " still looks like a placeholder (contains '" + fragment
                        + "') — a deployment placeholder reached the process unsubstituted. Supply "
                        + "the real key through " + envVar));
            }
        }
        int lengthBytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (lengthBytes < MIN_KEY_LENGTH) {
            throw new IllegalStateException(failure(
                    property + " is " + lengthBytes + " bytes — HS256 requires at least "
                    + MIN_KEY_LENGTH + ". Supply a longer " + envVar));
        }
    }

    static String failure(String reason) {
        return "auth-identity refuses to start: " + reason + ". (T0-6: the JWT signing key must "
                + "come from configuration and has no working default; a predictable signing key "
                + "means forgeable tokens.)";
    }

    /** Asserts a usable signing key SET is configured before the service accepts traffic. */
    static class JwtSigningKeyAssertion implements InitializingBean {

        private final String  signingSecret;
        private final String  previousKeysSpec;
        private final String  activatedAtSpec;
        private final boolean allowHardCutover;
        private final long    maxTokenTtlSeconds;

        /** Single-key form: no predecessors, no declared activation instant. */
        JwtSigningKeyAssertion(String signingSecret) {
            this(signingSecret, "", "", false, 3600L);
        }

        JwtSigningKeyAssertion(String signingSecret,
                               String previousKeysSpec,
                               String activatedAtSpec,
                               boolean allowHardCutover,
                               long maxTokenTtlSeconds) {
            this.signingSecret      = signingSecret;
            this.previousKeysSpec   = previousKeysSpec;
            this.activatedAtSpec    = activatedAtSpec;
            this.allowHardCutover   = allowHardCutover;
            this.maxTokenTtlSeconds = maxTokenTtlSeconds;
        }

        @Override
        public void afterPropertiesSet() {
            assertKeySet(Instant.now());
        }

        /** Package-private so tests can pin the time-dependent rotation rules deterministically. */
        JwtKeySet assertKeySet(Instant now) {
            if (signingSecret == null || signingSecret.isBlank()) {
                throw new IllegalStateException(failure(
                        "gme.auth.jwt.signing-secret is blank — set the GME_AUTH_JWT_SIGNING_SECRET "
                        + "environment variable to a random value of at least " + MIN_KEY_LENGTH
                        + " characters (e.g. `openssl rand -hex 32`). There is intentionally no "
                        + "default: this key signs every platform capability token, so a default "
                        + "means anyone with the repo can forge one. A key SET with no ACTIVE key "
                        + "is the same failure: gme.auth.jwt.previous-keys holds verification-only "
                        + "keys and can never substitute for the signing key"));
            }
            String activeSecret = signingSecret.trim();
            validateKeyMaterial(activeSecret, "gme.auth.jwt.signing-secret",
                                "GME_AUTH_JWT_SIGNING_SECRET");

            // Parse failures are configuration errors, and a configuration error in the key set is
            // a boot failure with the guard's own prefix — not a stack trace from the parser.
            List<JwtKeySet.Key> previous;
            try {
                previous = JwtKeySet.parsePreviousKeys(previousKeysSpec);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(failure(e.getMessage()), e);
            }

            // Every ACCEPTED key is live signing material for as long as it is accepted, so it gets
            // the identical treatment — this is the point at which "extend, do not weaken" is
            // actually enforced.
            for (JwtKeySet.Key key : previous) {
                validateKeyMaterial(key.secret(), "gme.auth.jwt.previous-keys (key " + key.kid() + ")",
                                    "GME_AUTH_JWT_PREVIOUS_KEYS");
                if (key.demotedAt().isAfter(now)) {
                    throw new IllegalStateException(failure(
                            "gme.auth.jwt.previous-keys key " + key.kid() + " declares a demoted-at "
                            + "of " + key.demotedAt() + ", which is in the FUTURE. A key that has "
                            + "not stopped signing yet cannot be in the verification-only set, and "
                            + "the retirement arithmetic ('safe to remove after demoted-at + one "
                            + "max token TTL') would run backwards"));
                }
            }

            JwtKeySet keySet;
            try {
                keySet = JwtKeySet.of(activeSecret, previous);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(failure(e.getMessage()), e);
            }

            Duration maxTtl = Duration.ofSeconds(Math.max(maxTokenTtlSeconds, 0));
            assertNotAnUndeclaredHardCutover(previous, maxTtl, now);
            logRetirementReport(keySet, maxTtl, now);
            return keySet;
        }

        /**
         * Refuses a rotation that dropped the outgoing key in the same step it promoted the new
         * one, unless the operator declared that intent.
         */
        private void assertNotAnUndeclaredHardCutover(List<JwtKeySet.Key> previous,
                                                      Duration maxTtl, Instant now) {
            if (activatedAtSpec == null || activatedAtSpec.isBlank()) {
                // Nothing declared, so nothing can be checked. Stated once at startup rather than
                // silently, so "we thought the guard had our back" is not available later.
                log.info("T0-6: gme.auth.jwt.active-key-activated-at is not set, so the "
                        + "premature-retirement check is INACTIVE. Set "
                        + "GME_AUTH_JWT_ACTIVE_KEY_ACTIVATED_AT to the instant the current key "
                        + "started signing (the rotation runbook does this) and a rotation that "
                        + "drops the outgoing key too early becomes a startup failure instead of a "
                        + "wave of rejected tokens.");
                return;
            }
            Instant activatedAt;
            try {
                activatedAt = Instant.parse(activatedAtSpec.trim());
            } catch (DateTimeParseException e) {
                throw new IllegalStateException(failure(
                        "gme.auth.jwt.active-key-activated-at ('" + activatedAtSpec + "') is not an "
                        + "ISO-8601 instant such as 2026-07-28T09:00:00Z"), e);
            }
            if (activatedAt.isAfter(now)) {
                throw new IllegalStateException(failure(
                        "gme.auth.jwt.active-key-activated-at is " + activatedAt + ", which is in "
                        + "the FUTURE — the active key cannot have started signing later than now"));
            }
            Instant overlapEndsAt = activatedAt.plus(maxTtl);
            if (!now.isBefore(overlapEndsAt) || !previous.isEmpty() || allowHardCutover) {
                return;
            }
            throw new IllegalStateException(failure(
                    "this looks like a HARD CUTOVER that was not declared as one. The active key "
                    + "started signing at " + activatedAt + ", less than one maximum token TTL ("
                    + maxTtl.toSeconds() + "s, until " + overlapEndsAt + ") ago, and "
                    + "gme.auth.jwt.previous-keys is EMPTY — so every token minted before the "
                    + "switch has just been invalidated and every session holding one is broken. "
                    + "Either (a) put the OUTGOING key back into GME_AUTH_JWT_PREVIOUS_KEYS as "
                    + "'<old-secret>@" + activatedAt + "' and remove it after " + overlapEndsAt
                    + " — the graceful rotation the runbook describes — or (b) set "
                    + "GME_AUTH_JWT_ALLOW_HARD_CUTOVER=true if the loss of every live token is "
                    + "intended, which it is for a first deployment and for a response to a "
                    + "suspected key compromise"));
        }

        /**
         * The startup evidence an operator uses to answer "did the rotation take, and when can I
         * delete the old key". One line per key; no key material, only derived ids.
         */
        private void logRetirementReport(JwtKeySet keySet, Duration maxTtl, Instant now) {
            log.info("T0-6 JWT key set: active kid={} ({} accepted predecessor(s), max token TTL {}s)",
                    keySet.activeKey().kid(), keySet.previousKeys().size(), maxTtl.toSeconds());
            for (JwtKeySet.KeyStatus status : keySet.report(maxTtl, now)) {
                if (status.active()) {
                    continue;
                }
                if (status.overdueForRemoval()) {
                    log.warn("T0-6 JWT key {} is OVERDUE FOR RETIREMENT: it stopped signing at {} "
                            + "and no token it signed can have been live since {}. Every extra day "
                            + "it stays in GME_AUTH_JWT_PREVIOUS_KEYS is another day a leaked copy "
                            + "of it still mints valid tokens. Remove the entry.",
                            status.kid(), status.demotedAt(), status.safeToRemoveAfter());
                } else if (status.safeToRemoveNow()) {
                    log.info("T0-6 JWT key {} (demoted {}) is now SAFE TO REMOVE from "
                            + "GME_AUTH_JWT_PREVIOUS_KEYS — its tokens all expired at {}.",
                            status.kid(), status.demotedAt(), status.safeToRemoveAfter());
                } else {
                    log.info("T0-6 JWT key {} (demoted {}) is accepted for verification only; "
                            + "safe to remove after {}.",
                            status.kid(), status.demotedAt(), status.safeToRemoveAfter());
                }
            }
        }
    }
}
