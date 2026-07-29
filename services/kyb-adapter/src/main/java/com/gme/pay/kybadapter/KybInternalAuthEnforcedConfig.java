package com.gme.pay.kybadapter;

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
 * Fail-closed guard for the internal-auth gate over kyb-adapter's KYB surface
 * (gap T1-4, using the T0-2 mechanism).
 *
 * <p>{@code POST /v1/kyb/screen} and {@code POST /v1/kyb/verify} accept a
 * partner's legal names, tax id and full UBO set (names, ownership percentages
 * and PEP flags) and return a compliance verdict that config-registry stores as
 * regulator-facing evidence; {@code GET /v1/kyb/result/{ref}} reads any stored
 * run back by reference. No api-gateway route fronts this service and its only
 * legitimate caller is config-registry, so the surface is internal-only.
 *
 * <p>Until this service became deployable at all (it had no Dockerfile and was in
 * no deploy target) the question was moot. Now that it ships in compose and the
 * chart, it must not serve KYB subjects to anything on the cluster network that
 * can reach port 8080.
 *
 * <p>The gate itself is the shared {@link InternalAuthFilter} installed by
 * lib-errors' {@link InternalAuthAutoConfiguration} — see
 * {@code application.properties}. Because that auto-configuration is opt-in,
 * "flag absent" and "flag false" both mean <em>no filter at all</em>, so this
 * bean asserts at startup that the gate is genuinely armed:
 *
 * <ul>
 *   <li>{@code gmepay.internal-auth.enabled} not {@code true} → the filter is
 *       never registered and KYB screening is anonymous. <b>Refuse to boot.</b></li>
 *   <li>{@code gmepay.internal-auth.secret} absent or blank → not a credential;
 *       there is deliberately <b>no default in the repo</b>. <b>Refuse to boot.</b></li>
 *   <li>{@code path-patterns} narrowed so a KYB route falls outside the gate →
 *       <b>refuse to boot</b>. Operators may <em>add</em> patterns, never drop these.</li>
 * </ul>
 *
 * <p>{@code GET /v1/kyb/health} is deliberately NOT in the required set: it
 * carries no partner data and reports whether the active provider is
 * authoritative, which a container probe and an operator should both be able to
 * read.
 */
@Configuration
public class KybInternalAuthEnforcedConfig {

    /** Path patterns that MUST remain gated — every route that touches a KYB subject or verdict. */
    static final List<String> REQUIRED_PATTERNS =
            List.of("/v1/kyb/screen", "/v1/kyb/verify", "/v1/kyb/result/**");

    @Bean
    InternalAuthGateAssertion kybInternalAuthGateAssertion(
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
                        "gmepay.internal-auth.enabled must be true — the KYB screening surface "
                        + "carries partner UBO / tax-id data and must never be served anonymously"));
            }
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException(failure(
                        "gmepay.internal-auth.secret is blank — set the GMEPAY_INTERNAL_AUTH_SECRET "
                        + "environment variable to the platform's shared internal token (the same value "
                        + "config-registry presents in the " + InternalAuthHeaders.INTERNAL_TOKEN
                        + " header). There is intentionally no default"));
            }
            List<String> missing = REQUIRED_PATTERNS.stream().filter(p -> !patterns.contains(p)).toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException(failure(
                        "gmepay.internal-auth.path-patterns no longer gates " + missing
                        + " — that would leave partner KYB subjects and verdicts anonymous. Patterns "
                        + "may be added, never removed"));
            }
        }

        private static String failure(String reason) {
            return "kyb-adapter refuses to start: " + reason + ". (T1-4: the KYB surface requires the "
                    + "service-to-service internal-auth token; see "
                    + InternalAuthProperties.class.getName() + ".)";
        }
    }
}
