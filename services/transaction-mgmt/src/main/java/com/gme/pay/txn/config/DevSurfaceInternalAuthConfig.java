package com.gme.pay.txn.config;

import com.gme.pay.internalauth.InternalAuthFilter;
import com.gme.pay.internalauth.InternalAuthHeaders;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Internal-auth gate for transaction-mgmt's <b>dev</b> surface (T0-2).
 *
 * <p>transaction-mgmt's production surface ({@code /v1/transactions/**}) is partner- and
 * operator-facing and is authenticated at the api-gateway; it is deliberately NOT touched here.
 * What this config covers is the one surface that exists only for debugging and that no external
 * caller may ever reach:
 *
 * <ul>
 *   <li>{@code /__data/**} — {@link com.gme.pay.txn.devtools.DevDataController}, a read-only dump of
 *       <b>every table in the schema</b>, which for this service is the transaction ledger: payer and
 *       merchant identifiers, amounts, QR payloads, scheme references, refunds. It is already
 *       {@code @ConditionalOnProperty(gmepay.devtools.enabled)} and off by default, but until now it
 *       was completely <b>unauthenticated whenever it was on</b> — and "on" is exactly the local /
 *       tunnelled posture where someone else can reach the port.</li>
 * </ul>
 *
 * <p>Reuses the platform mechanism ({@code com.gme.pay.internalauth}, #90) rather than inventing a
 * second scheme: a caller must present the shared secret in the {@code X-Gme-Internal} header or the
 * request is refused {@code 401} before the controller runs. lib-errors'
 * {@code InternalAuthAutoConfiguration} is not used because it gates a single global pattern list for
 * the whole service, and here the pattern list has to follow the dev flag — gating
 * {@code /v1/transactions/**} would break the legitimate partner/operator surface.
 *
 * <h2>Fail-closed</h2>
 * <p>Turning the dev surface on <b>without</b> {@code GMEPAY_INTERNAL_AUTH_SECRET} would leave the
 * ledger dump anonymous, so it is a startup failure instead. There is no default secret in the repo.
 * With the flag off (the production posture) the filter is registered disabled and no secret is
 * required — normal boots are unaffected.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DevSurfaceInternalAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(DevSurfaceInternalAuthConfig.class);

    static final String DEVTOOLS_PATTERN = "/__data/**";

    /**
     * Introspection surfaces that leak the internal API shape and per-partner counters:
     * {@code /actuator/metrics} (transaction counts), {@code /v3/api-docs} + Swagger UI (every route
     * and DTO). Gated <b>opportunistically</b> — only when a secret happens to be configured, never
     * in a bare local dev run. They are deliberately NOT part of the fail-closed rule: they expose no
     * ledger rows, and making a secret mandatory for every transaction-mgmt boot is a deployment
     * change beyond this fix. {@code /actuator/health/**} and {@code /actuator/info} stay anonymous
     * for container probes.
     */
    static final List<String> INTROSPECTION_PATTERNS =
            List.of("/actuator/metrics/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html");

    /**
     * Registers the shared {@link InternalAuthFilter} over the dev surface when it is enabled.
     * When it is not, the registration is left disabled so the filter never runs.
     *
     * @throws IllegalStateException when the dev surface is enabled but no internal secret is set
     */
    @Bean
    public FilterRegistrationBean<InternalAuthFilter> devSurfaceInternalAuthFilter(
            @Value("${gmepay.devtools.enabled:false}") boolean devtoolsEnabled,
            @Value("${gmepay.internal-auth.secret:}") String secret) {

        boolean haveSecret = secret != null && !secret.isBlank();

        // Fail-closed: the dev surface without a secret is an anonymous dump of the transaction ledger.
        if (devtoolsEnabled && !haveSecret) {
            throw new IllegalStateException(
                    "transaction-mgmt refuses to start: gmepay.devtools.enabled=true exposes "
                    + DEVTOOLS_PATTERN + " (a read-only dump of every table, including the "
                    + "transaction ledger) but gmepay.internal-auth.secret is blank, which would "
                    + "serve it to anyone who can reach this port. Set the "
                    + "GMEPAY_INTERNAL_AUTH_SECRET environment variable (the platform's shared "
                    + "internal token, presented in the " + InternalAuthHeaders.INTERNAL_TOKEN
                    + " header) or turn the surface off (gmepay.devtools.enabled=false). There is "
                    + "intentionally no default value.");
        }

        List<String> patterns = new ArrayList<>();
        if (devtoolsEnabled) {
            patterns.add(DEVTOOLS_PATTERN);
        }
        if (haveSecret) {
            patterns.addAll(INTROSPECTION_PATTERNS);
        } else {
            log.debug("gmepay.internal-auth.secret is not set — {} stay anonymous on this instance.",
                    INTROSPECTION_PATTERNS);
        }

        FilterRegistrationBean<InternalAuthFilter> reg =
                new FilterRegistrationBean<>(new InternalAuthFilter(secret, patterns));
        reg.setName("devSurfaceInternalAuthFilter");
        reg.addUrlPatterns("/*");
        // Same slot as the lib-errors gate: ahead of the RBAC context filter and of MVC dispatch.
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.setEnabled(!patterns.isEmpty());

        if (devtoolsEnabled) {
            log.warn("DEV SURFACE ENABLED: {} is served by this instance, gated behind the {} "
                    + "internal-auth token. This must never be true in production.",
                    DEVTOOLS_PATTERN, InternalAuthHeaders.INTERNAL_TOKEN);
        }
        return reg;
    }
}
