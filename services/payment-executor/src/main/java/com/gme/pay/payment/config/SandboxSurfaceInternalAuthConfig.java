package com.gme.pay.payment.config;

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
 * Internal-auth gate for payment-executor's <b>dev / sandbox</b> surfaces (T0-5 / CISO#6).
 *
 * <p>payment-executor's production pay surface ({@code /v1/payments/**}, {@code /v1/pay/**}) is
 * partner-facing and authenticated at the api-gateway — it is deliberately NOT touched here. What
 * this config covers is {@code GET /v1/balance} (see below) plus the two surfaces that exist only for
 * debugging and that no external caller may ever reach:
 *
 * <ul>
 *   <li>{@code /v1/sandbox/e2e/**} — the E2E <b>payment runner</b>. {@code POST /run} executes a
 *       real authorize+capture through the real pay path (prefunding debit, scheme call, ledger
 *       postings), i.e. an unauthenticated "spend money" button. Gated on
 *       {@code gmepay.sandbox.e2e.enabled} (default {@code false}: the controller bean does not
 *       even exist, so the path 404s).</li>
 *   <li>{@code /__data/**} — {@code DevDataController}, a read-only dump of every table in the
 *       schema (payment rows, partner ids, amounts). Gated on {@code gmepay.devtools.enabled}
 *       (default {@code false}, already conditional on that flag).</li>
 * </ul>
 *
 * <p>Reuses the platform mechanism ({@code com.gme.pay.internalauth}, #90) rather than inventing a
 * second scheme: a caller must present the shared secret in the {@code X-Gme-Internal} header or
 * the request is refused {@code 401} before the controller runs. lib-errors'
 * {@code InternalAuthAutoConfiguration} is not used because it would gate a single global pattern
 * list for the whole service; here the pattern list has to follow whichever dev flags are on.
 *
 * <h2>Fail-closed</h2>
 * <p>Turning a dev surface on <b>without</b> {@code GMEPAY_INTERNAL_AUTH_SECRET} would leave that
 * surface anonymous, so it is a startup failure instead. There is no default secret in the repo.
 * With both flags off (the production posture) the filter is registered disabled and no secret is
 * required — normal production boots are unaffected.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SandboxSurfaceInternalAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(SandboxSurfaceInternalAuthConfig.class);

    static final String SANDBOX_E2E_PATTERN = "/v1/sandbox/e2e/**";
    static final String DEVTOOLS_PATTERN = "/__data/**";

    /**
     * {@code GET /v1/balance} — an <b>internal</b> partner-float inquiry, gated <b>unconditionally</b>
     * (T0-2).
     *
     * <p>It reads any partner's prefunding balance and deduction history straight out of the now-gated
     * prefunding service, and it identified the partner purely from caller-supplied headers with
     * fail-open defaults ({@code X-Partner-Id} defaulting to {@code 1}, {@code X-Partner-Type}
     * defaulting to {@code OVERSEAS}) — a header-swap IDOR over every partner's float.
     *
     * <p>The intended caller model was ambiguous (wallet? partner? internal?), so this takes the most
     * restrictive reading that breaks nothing: <b>internal-only</b>. Evidence it is internal:
     * the api-gateway does not route it at all (the payment-executor route is {@code /v1/payments/**}
     * and nothing matches {@code /v1/balance}), and no caller exists anywhere in the repo — not
     * admin-ui, not partner-portal-ui, not ops-partner-bff, not the e2e suites. Requiring the internal
     * token therefore costs no working traffic, whereas inventing a public auth model here would
     * duplicate the gateway's HMAC/JWT boundary in the wrong layer.
     *
     * <p>Unlike the dev surfaces this is NOT conditional on a flag and NOT conditional on a secret
     * being present: with a blank secret the filter still runs over this path and
     * {@code InternalAuthFilter} refuses <em>every</em> credential (an empty configured secret can
     * never equal a presented one), so the endpoint answers 401 to all callers. That is fail-closed
     * without making a secret mandatory for every payment-executor boot — a bare local run keeps
     * serving payments, it just cannot read balances.
     *
     * <p>Tenancy is a separate concern also fixed under T0-2: {@code BalanceController} no longer
     * defaults the partner and no longer trusts an {@code X-Partner-Type} claim.
     */
    static final String BALANCE_PATTERN = "/v1/balance";

    /**
     * Introspection surfaces that leak the internal API shape and per-partner counters:
     * {@code /actuator/metrics} (payment counts, decline rates), {@code /v3/api-docs} + Swagger UI
     * (every route and DTO). Gated <b>opportunistically</b> — only when a secret happens to be
     * configured, which it always is in a real deployment (payment-executor needs it anyway to call
     * the now-gated prefunding service), never in a bare local dev run. They are deliberately NOT
     * part of the fail-closed rule: unlike the sandbox runner these move no money, and making a
     * secret mandatory for every payment-executor boot is a deployment change outside this fix.
     * {@code /actuator/health/**} and {@code /actuator/info} stay anonymous for container probes.
     */
    static final List<String> INTROSPECTION_PATTERNS =
            List.of("/actuator/metrics/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html");

    /**
     * Registers the shared {@link InternalAuthFilter} over whichever dev/sandbox surfaces are
     * enabled. When none are, the registration is left disabled so the filter never runs.
     *
     * @throws IllegalStateException when a dev surface is enabled but no internal secret is set
     */
    @Bean
    public FilterRegistrationBean<InternalAuthFilter> sandboxSurfaceInternalAuthFilter(
            @Value("${gmepay.sandbox.e2e.enabled:false}") boolean sandboxE2eEnabled,
            @Value("${gmepay.devtools.enabled:false}") boolean devtoolsEnabled,
            @Value("${gmepay.internal-auth.secret:}") String secret) {

        List<String> devSurfaces = new ArrayList<>();
        if (sandboxE2eEnabled) {
            devSurfaces.add(SANDBOX_E2E_PATTERN);
        }
        if (devtoolsEnabled) {
            devSurfaces.add(DEVTOOLS_PATTERN);
        }

        boolean haveSecret = secret != null && !secret.isBlank();

        // Fail-closed: a dev surface without a secret would be an anonymous payment runner / table dump.
        if (!devSurfaces.isEmpty() && !haveSecret) {
            throw new IllegalStateException(
                    "payment-executor refuses to start: a dev/sandbox surface is enabled ("
                    + devSurfaces + ") but gmepay.internal-auth.secret is blank, which would expose "
                    + (devSurfaces.contains(SANDBOX_E2E_PATTERN)
                            ? "an unauthenticated payment runner" : "an unauthenticated table dump")
                    + " to anyone who can reach this port. Set the GMEPAY_INTERNAL_AUTH_SECRET "
                    + "environment variable (the platform's shared internal token, presented in the "
                    + InternalAuthHeaders.INTERNAL_TOKEN + " header) or turn the surface off "
                    + "(gmepay.sandbox.e2e.enabled=false / gmepay.devtools.enabled=false). "
                    + "There is intentionally no default value.");
        }

        List<String> patterns = new ArrayList<>(devSurfaces);
        // Always gated, secret or not: with a blank secret the filter refuses every credential, so
        // the balance inquiry answers 401 to everyone rather than leaking any partner's float.
        patterns.add(BALANCE_PATTERN);
        if (haveSecret) {
            patterns.addAll(INTROSPECTION_PATTERNS);
        } else {
            log.warn("gmepay.internal-auth.secret is not set — {} stay anonymous on this instance, and "
                    + "GET {} will refuse EVERY caller (401) because no token can match a blank "
                    + "secret. Set GMEPAY_INTERNAL_AUTH_SECRET in any deployment reachable beyond "
                    + "localhost.", INTROSPECTION_PATTERNS, BALANCE_PATTERN);
        }

        FilterRegistrationBean<InternalAuthFilter> reg =
                new FilterRegistrationBean<>(new InternalAuthFilter(secret, patterns));
        reg.setName("sandboxSurfaceInternalAuthFilter");
        reg.addUrlPatterns("/*");
        // Same slot as the lib-errors gate: ahead of the RBAC context filter and of MVC dispatch.
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        reg.setEnabled(!patterns.isEmpty());

        if (devSurfaces.isEmpty()) {
            log.debug("sandbox/devtools surfaces disabled; internal-auth filter gates {}", patterns);
        } else {
            log.warn("DEV SURFACE ENABLED: {} is served by this instance, gated behind the {} "
                    + "internal-auth token. This must never be true in production.",
                    devSurfaces, InternalAuthHeaders.INTERNAL_TOKEN);
        }
        return reg;
    }
}
