package com.gme.pay.scheme.zeropay.config;

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
 * Fail-closed guard for the service-to-service internal-auth gate (T0-2).
 *
 * <p>This adapter has <b>no public surface</b>. Everything it exposes lives under
 * {@code /internal/scheme/zeropay} and drives, or gates, real money movement at KFTC:
 *
 * <ul>
 *   <li>{@code POST /submit}, {@code POST /cpm} — <b>authorise + commit</b> a payment at the scheme.
 *       {@code POST /cancel} reverses one. An anonymous caller here moves funds at the partner
 *       network, outside GMEPay+'s own ledger.</li>
 *   <li>{@code POST /balance-check}, {@code GET /status}, {@code GET /health} — pre-submit and
 *       anti-double-charge lookups; they disclose GME's prepaid position with the scheme and the
 *       state of individual references.</li>
 *   <li>{@code GET /registration-status} — the settlement prerequisite projection
 *       settlement-reconciliation consults before generating a ZP0061/ZP0063 settlement request.
 *       Forging a positive answer un-blocks settlement generation.</li>
 *   <li>{@code /__data/**} — {@code DevDataController}, a raw dump of {@code zp_committed_txns} /
 *       {@code zp_batch_files}. Already {@code @ConditionalOnProperty(gmepay.devtools.enabled)} and
 *       off by default, but unauthenticated whenever it was on.</li>
 * </ul>
 *
 * <p>The actual gate is the shared {@link InternalAuthFilter} installed by lib-errors'
 * {@link InternalAuthAutoConfiguration} — see {@code application.properties}. The legitimate callers
 * are payment-executor's {@code RestSchemeClient} and settlement-reconciliation's
 * {@code RestRegistrationStatusClient}.
 *
 * <p>That auto-configuration is opt-in ({@code gmepay.internal-auth.enabled=true}) so the services
 * without an internal surface are unaffected — which means for <em>this</em> service a missing or
 * falsified flag would silently produce an <b>ungated scheme-submission API</b>. This bean closes
 * that hole: it asserts at startup that the gate is genuinely armed and refuses to start otherwise.
 *
 * <ul>
 *   <li>{@code gmepay.internal-auth.enabled} not {@code true} → the filter is never registered and
 *       every scheme submission is anonymous. <b>Refuse to boot.</b></li>
 *   <li>{@code gmepay.internal-auth.secret} absent or blank → not a credential. There is deliberately
 *       <b>no default in the repo</b>; it must come from {@code GMEPAY_INTERNAL_AUTH_SECRET}.
 *       <b>Refuse to boot.</b></li>
 *   <li>{@code path-patterns} narrowed so {@code /internal/**} falls outside the gate →
 *       <b>refuse to boot</b>. Operators may <em>add</em> patterns, never drop that one.</li>
 * </ul>
 *
 * <p>Startup failure is the correct outcome rather than a WARN: an unreachable adapter declines
 * payments (fail-closed), whereas an ungated one lets an anonymous caller commit money at KFTC.
 */
@Configuration
public class InternalAuthEnforcedConfig {

    /**
     * Path patterns that MUST remain gated. {@code /internal/**} covers the whole scheme surface;
     * the {@code /__data} and actuator/api-docs patterns in {@code application.properties} are
     * hardening extras (both are additionally flag-gated or read-only) and are not required here.
     */
    static final List<String> REQUIRED_PATTERNS = List.of("/internal/**");

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
                        "gmepay.internal-auth.enabled must be true — this adapter exposes only "
                        + "internal scheme endpoints that authorise, commit and cancel real money at "
                        + "the scheme, and must never serve them anonymously"));
            }
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException(failure(
                        "gmepay.internal-auth.secret is blank — set the GMEPAY_INTERNAL_AUTH_SECRET "
                        + "environment variable to the platform's shared internal token (the same value "
                        + "payment-executor and settlement-reconciliation present in the "
                        + InternalAuthHeaders.INTERNAL_TOKEN + " header). There is intentionally no "
                        + "default"));
            }
            List<String> missing = REQUIRED_PATTERNS.stream().filter(p -> !patterns.contains(p)).toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException(failure(
                        "gmepay.internal-auth.path-patterns no longer gates " + missing
                        + " — that would leave the scheme-submission API anonymous. Patterns may be "
                        + "added, never removed"));
            }
        }

        private static String failure(String reason) {
            return "scheme-adapter-zeropay refuses to start: " + reason + ". (T0-2: every "
                    + "/internal/scheme endpoint requires the service-to-service internal-auth token; "
                    + "see " + InternalAuthProperties.class.getName() + ".)";
        }
    }
}
