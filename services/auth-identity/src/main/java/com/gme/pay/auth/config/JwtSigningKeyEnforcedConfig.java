package com.gme.pay.auth.config;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
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
 */
@Configuration
public class JwtSigningKeyEnforcedConfig {

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
            @Value("${gme.auth.jwt.signing-secret:}") String signingSecret) {
        return new JwtSigningKeyAssertion(signingSecret);
    }

    /** Asserts a usable signing key is configured before the service accepts traffic. */
    static class JwtSigningKeyAssertion implements InitializingBean {

        private final String signingSecret;

        JwtSigningKeyAssertion(String signingSecret) {
            this.signingSecret = signingSecret;
        }

        @Override
        public void afterPropertiesSet() {
            if (signingSecret == null || signingSecret.isBlank()) {
                throw new IllegalStateException(failure(
                        "gme.auth.jwt.signing-secret is blank — set the GME_AUTH_JWT_SIGNING_SECRET "
                        + "environment variable to a random value of at least " + MIN_KEY_LENGTH
                        + " characters (e.g. `openssl rand -hex 32`). There is intentionally no "
                        + "default: this key signs every platform capability token, so a default "
                        + "means anyone with the repo can forge one"));
            }
            String key = signingSecret.trim();
            if (PUBLISHED_KEYS.contains(key)) {
                throw new IllegalStateException(failure(
                        "gme.auth.jwt.signing-secret is a value that has been PUBLISHED in this "
                        + "repository and is therefore compromised. Generate a fresh key and supply "
                        + "it through GME_AUTH_JWT_SIGNING_SECRET"));
            }
            String lower = key.toLowerCase(Locale.ROOT);
            for (String fragment : PLACEHOLDER_FRAGMENTS) {
                if (lower.contains(fragment)) {
                    throw new IllegalStateException(failure(
                            "gme.auth.jwt.signing-secret still looks like a placeholder "
                            + "(contains '" + fragment + "') — a deployment placeholder reached the "
                            + "process unsubstituted. Supply the real key through "
                            + "GME_AUTH_JWT_SIGNING_SECRET"));
                }
            }
            int lengthBytes = key.getBytes(StandardCharsets.UTF_8).length;
            if (lengthBytes < MIN_KEY_LENGTH) {
                throw new IllegalStateException(failure(
                        "gme.auth.jwt.signing-secret is " + lengthBytes + " bytes — HS256 requires at "
                        + "least " + MIN_KEY_LENGTH + ". Supply a longer GME_AUTH_JWT_SIGNING_SECRET"));
            }
        }

        private static String failure(String reason) {
            return "auth-identity refuses to start: " + reason + ". (T0-6: the JWT signing key must "
                    + "come from configuration and has no working default; a predictable signing key "
                    + "means forgeable tokens.)";
        }
    }
}
