package com.gme.pay.prefunding.config;

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
 * Fail-closed guard for the service-to-service internal-auth gate (T0-5 / CISO#6).
 *
 * <p>prefunding has no public surface. Every endpoint either <b>moves</b> partner float
 * ({@code POST /v1/prefunding/{id}/deduct|credit|reverse|reserve|capture|release|cumulative-*},
 * {@code PUT .../credit-limit}, {@code POST /v1/prefunding/provision},
 * {@code POST /internal/v1/prefunding/{id}/deduct|reverse|reserve|release}) or <b>reads</b> it
 * ({@code GET .../balance|alerts|deductions}). The only legitimate callers are other GMEPay+
 * services. The actual gate is the shared {@link InternalAuthFilter} installed by lib-errors'
 * {@link InternalAuthAutoConfiguration} — see {@code application.properties}.
 *
 * <p>That auto-configuration is opt-in ({@code gmepay.internal-auth.enabled=true}) precisely so
 * services without an internal surface are unaffected — which means for <em>this</em> service a
 * missing or falsified flag would silently produce an <b>ungated money API</b>. This bean closes
 * that hole: it asserts at startup that the gate is genuinely armed, and refuses to start
 * otherwise. Consequences of the two failure modes it covers:
 *
 * <ul>
 *   <li>{@code gmepay.internal-auth.enabled} flipped to {@code false} (e.g. a stray
 *       {@code GMEPAY_INTERNAL_AUTH_ENABLED=false} in a deployment env) → the filter is never
 *       registered and every balance mutation is anonymous. <b>Refuse to boot.</b></li>
 *   <li>{@code gmepay.internal-auth.secret} absent or blank → nothing can ever present a matching
 *       token, and (more importantly) a blank shared secret is not a credential. There is
 *       deliberately <b>no default value in the repo</b>: the secret must come from the
 *       {@code GMEPAY_INTERNAL_AUTH_SECRET} environment variable. <b>Refuse to boot.</b>
 *       (lib-errors' own validator covers this case too when the gate is enabled; asserted here
 *       as well so the "enabled=false + no secret" combination cannot slip through either.)</li>
 *   <li>The gated path list no longer covers the money surface → <b>refuse to boot</b>. Operators
 *       may <em>add</em> patterns, never drop {@code /internal/**} or {@code /v1/prefunding/**}.</li>
 * </ul>
 *
 * <p>Startup failure is the correct outcome rather than a WARN: an unreachable prefunding service
 * declines payments (fail-closed), whereas an ungated one lets an anonymous caller debit float.
 */
@Configuration
public class InternalAuthEnforcedConfig {

    /**
     * Path patterns that MUST remain gated. These are the money-moving / float-reading surfaces;
     * the actuator + api-docs patterns in {@code application.properties} are hardening extras and
     * are deliberately not required here.
     */
    static final List<String> REQUIRED_PATTERNS = List.of("/internal/**", "/v1/prefunding/**");

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
                        "gmepay.internal-auth.enabled must be true — prefunding exposes only "
                        + "money-moving/float-reading endpoints and must never serve them anonymously"));
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
                        + " — that would leave the balance API anonymous. Patterns may be added, "
                        + "never removed"));
            }
        }

        private static String failure(String reason) {
            return "prefunding refuses to start: " + reason + ". (T0-5: every prefunding endpoint "
                    + "requires the service-to-service internal-auth token; see "
                    + InternalAuthProperties.class.getName() + ".)";
        }
    }
}
