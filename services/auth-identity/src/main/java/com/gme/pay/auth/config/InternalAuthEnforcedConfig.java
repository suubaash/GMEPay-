package com.gme.pay.auth.config;

import com.gme.pay.internalauth.InternalAuthAutoConfiguration;
import com.gme.pay.internalauth.InternalAuthFilter;
import com.gme.pay.internalauth.InternalAuthHeaders;
import com.gme.pay.internalauth.InternalAuthProperties;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Fail-closed guard for the service-to-service internal-auth gate (T0-2, hardening #90).
 *
 * <p>auth-identity has <b>no public surface</b>. Every controller it ships is internal-only and, in
 * severity order:
 *
 * <ul>
 *   <li>{@code POST /internal/auth/token/issue} — <b>mints JWTs</b>. An anonymous caller here does
 *       not merely read data, it becomes an arbitrary principal everywhere the platform trusts a
 *       GME-signed token.</li>
 *   <li>{@code POST /internal/auth/keys}, {@code .../{keyId}/revoke}, {@code .../rotate},
 *       {@code .../resolve} — <b>issues, rotates and resolves partner API keys and webhook
 *       secrets</b> (the real-credential path T1-1 depends on). Anonymous access means self-issuing
 *       partner machine credentials, or reading an existing partner's.</li>
 *   <li>{@code POST /internal/auth/verify} — HMAC signature verification oracle.</li>
 *   <li>{@code /v1/rbac/**} — RBAC resolution + catalogue management (enumerate or mutate the
 *       authority matrix).</li>
 *   <li>{@code /v1/approvals/**} — approval decisions, which trust an {@code X-Gme-Permissions}
 *       header, so an unauthenticated caller can forge a dual-control approval.</li>
 * </ul>
 *
 * <p>The actual gate is the shared {@link InternalAuthFilter} installed by lib-errors'
 * {@link InternalAuthAutoConfiguration} — configured in {@code application.yml}. The only legitimate
 * callers (api-gateway's claim resolver, config-registry's auth-identity client, the ops BFF's RBAC /
 * approval / sandbox-key clients) already present the shared token.
 *
 * <h2>Why this bean exists</h2>
 *
 * <p>That auto-configuration is deliberately <em>opt-in</em>
 * ({@code gmepay.internal-auth.enabled=true}) so the 20 services without an internal surface are
 * unaffected. The consequence is that for <em>this</em> service "flag absent" and "flag false" both
 * mean <b>no filter at all</b> — and until T0-2 the flag was
 * {@code ${GMEPAY_INTERNAL_AUTH_ENABLED:false}}, i.e. an unconfigured deployment (bare
 * {@code bootRun}, hand-rolled compose, a new environment that forgot one env var) published
 * anonymous JWT minting and anonymous API-key issuance. docker-compose and Helm happened to set it,
 * which is not a security control — it is a coincidence of two files.
 *
 * <p>The flag is now pinned {@code true} in {@code application.yml} and this bean asserts, during
 * context refresh, that the gate is genuinely armed. Each failure mode is fatal:
 *
 * <ul>
 *   <li>{@code gmepay.internal-auth.enabled} overridden to {@code false} (e.g. a stray
 *       {@code GMEPAY_INTERNAL_AUTH_ENABLED=false}) → the filter is never registered.
 *       <b>Refuse to boot</b> instead of serving credential issuance anonymously.</li>
 *   <li>{@code gmepay.internal-auth.secret} absent or blank → a blank shared secret is not a
 *       credential. There is deliberately <b>no default value in the repo</b>: it must come from the
 *       {@code GMEPAY_INTERNAL_AUTH_SECRET} environment variable. <b>Refuse to boot.</b>
 *       (lib-errors' own validator covers this when the gate is enabled; asserted here too so the
 *       "enabled=false + no secret" combination cannot slip through either.)</li>
 *   <li>{@code path-patterns} narrowed so one of the internal surfaces falls outside the gate →
 *       <b>refuse to boot</b>. Operators may <em>add</em> patterns, never drop these.</li>
 * </ul>
 *
 * <p>Startup failure is the correct outcome rather than a WARN: an unreachable auth-identity fails
 * every login and every signed partner call closed, whereas an ungated one hands out identities.
 */
@Configuration
public class InternalAuthEnforcedConfig {

    /**
     * Path patterns that MUST remain gated: the whole {@code /internal/**} machine surface (JWT
     * minting, API-key issuance, HMAC verify), RBAC, and approvals. The actuator/api-docs patterns
     * in {@code application.yml} are hardening extras and are deliberately not required here.
     */
    static final List<String> REQUIRED_PATTERNS =
            List.of("/internal/**", "/v1/rbac/**", "/v1/approvals/**");

    @Bean
    InternalAuthGateAssertion internalAuthGateAssertion(
            @Value("${gmepay.internal-auth.enabled:false}") boolean enabled,
            @Value("${gmepay.internal-auth.secret:}") String secret,
            @Value("${gmepay.internal-auth.path-patterns:}") List<String> patterns) {
        return new InternalAuthGateAssertion(enabled, secret, patterns);
    }

    /** Asserts the gate is armed before the service accepts traffic; see the class javadoc. */
    static class InternalAuthGateAssertion implements InitializingBean {

        private final boolean enabled;
        private final String secret;
        private final List<String> patterns;

        InternalAuthGateAssertion(boolean enabled, String secret, List<String> patterns) {
            this.enabled = enabled;
            this.secret = secret;
            this.patterns = patterns == null ? List.of() : patterns;
        }

        @Override
        public void afterPropertiesSet() {
            if (!enabled) {
                throw new IllegalStateException(failure(
                        "gmepay.internal-auth.enabled must be true — auth-identity exposes only "
                        + "internal-only endpoints (JWT minting, partner API-key issuance, RBAC, "
                        + "approvals) and must never serve them anonymously"));
            }
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException(failure(
                        "gmepay.internal-auth.secret is blank — set the GMEPAY_INTERNAL_AUTH_SECRET "
                        + "environment variable to the platform's shared internal token (the same value "
                        + "the calling services present in the " + InternalAuthHeaders.INTERNAL_TOKEN
                        + " header). There is intentionally no default"));
            }
            List<String> missing = REQUIRED_PATTERNS.stream().filter(p -> !patterns.contains(p)).toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException(failure(
                        "gmepay.internal-auth.path-patterns no longer gates " + missing
                        + " — that would leave token minting / credential issuance / RBAC anonymous. "
                        + "Patterns may be added, never removed"));
            }
        }

        private static String failure(String reason) {
            return "auth-identity refuses to start: " + reason + ". (T0-2: every auth-identity "
                    + "endpoint requires the service-to-service internal-auth token; see "
                    + InternalAuthProperties.class.getName() + ".)";
        }
    }
}
