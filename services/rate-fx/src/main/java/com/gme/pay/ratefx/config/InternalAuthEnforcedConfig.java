package com.gme.pay.ratefx.config;

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
 * Fail-closed guard for the internal-auth gate over rate-fx's operator surface (T0-2).
 *
 * <p>{@code POST /v1/rates/snapshots}
 * ({@link com.gme.pay.ratefx.web.RateSnapshotAdminController}) appends an effective-dated treasury
 * rate, and <b>the latest snapshot wins at resolution time</b> — so a single anonymous write
 * re-prices every subsequent quote and payment in that currency. The controller's own javadoc
 * asserted the path was "intended to sit behind the internal-auth gate (operator-only)"; it was not,
 * and the api-gateway does not front it either, because the gateway route is the <em>exact</em> path
 * {@code /v1/rates} rather than {@code /v1/rates/**}.
 *
 * <p>The gate itself is the shared {@link InternalAuthFilter} installed by lib-errors'
 * {@link InternalAuthAutoConfiguration} — see {@code application.properties}. rate-fx's <b>public</b>
 * partner surface ({@code POST /v1/rates}, {@code /v1/quotes/**}) is deliberately outside the gated
 * pattern list: it is authenticated at the api-gateway and gating it here would break partner
 * traffic.
 *
 * <p>Because that auto-configuration is opt-in, "flag absent" and "flag false" both mean <em>no
 * filter at all</em>. This bean therefore asserts at startup that the gate is genuinely armed and
 * refuses to start otherwise:
 *
 * <ul>
 *   <li>{@code gmepay.internal-auth.enabled} not {@code true} → the filter is never registered and
 *       rate overrides are anonymous. <b>Refuse to boot.</b></li>
 *   <li>{@code gmepay.internal-auth.secret} absent or blank → not a credential. There is deliberately
 *       <b>no default in the repo</b>; it must come from {@code GMEPAY_INTERNAL_AUTH_SECRET}.
 *       <b>Refuse to boot.</b></li>
 *   <li>{@code path-patterns} narrowed so the snapshot path falls outside the gate → <b>refuse to
 *       boot</b>. Operators may <em>add</em> patterns, never drop these.</li>
 * </ul>
 *
 * <p>Startup failure is the correct outcome rather than a WARN: an unreachable rate-fx declines
 * quoting (fail-closed), whereas an ungated one lets an anonymous caller set the rate GME trades at.
 */
@Configuration
public class InternalAuthEnforcedConfig {

    /**
     * Path patterns that MUST remain gated: the snapshot-write surface, in both the bare and
     * wildcard forms configured in {@code application.properties}. The actuator/api-docs patterns
     * there are hardening extras and are not required here.
     */
    static final List<String> REQUIRED_PATTERNS =
            List.of("/v1/rates/snapshots", "/v1/rates/snapshots/**");

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
                        "gmepay.internal-auth.enabled must be true — POST /v1/rates/snapshots "
                        + "re-prices every subsequent quote and payment in a currency and must never "
                        + "be served anonymously"));
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
                        + " — that would leave the treasury-rate override anonymous. Patterns may be "
                        + "added, never removed"));
            }
        }

        private static String failure(String reason) {
            return "rate-fx refuses to start: " + reason + ". (T0-2: the rate-snapshot admin surface "
                    + "requires the service-to-service internal-auth token; see "
                    + InternalAuthProperties.class.getName() + ".)";
        }
    }
}
