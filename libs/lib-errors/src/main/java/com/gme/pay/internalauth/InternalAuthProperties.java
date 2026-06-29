package com.gme.pay.internalauth;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the service-to-service internal-auth gate. Disabled by default — a
 * service opts in with {@code gmepay.internal-auth.enabled=true} and a non-blank
 * {@code secret} (the {@link InternalAuthAutoConfiguration} fails the service closed if it
 * is enabled without a secret, so the gate can never silently no-op in production).
 *
 * <p>Only requests whose path matches one of {@link #pathPatterns} are gated; everything else
 * (health, actuator, swagger, the service's own public surface) is untouched. The defaults
 * cover the auth-identity internal surface ({@code /v1/rbac/**}, {@code /v1/approvals/**}).
 */
@ConfigurationProperties(prefix = "gmepay.internal-auth")
public class InternalAuthProperties {

    /** Master switch for the {@link InternalAuthFilter}. */
    private boolean enabled = false;

    /**
     * Shared secret a trusted in-cluster caller (gateway resolver / ops BFF) sends in the
     * {@code X-Gme-Internal} header. Blank is rejected at startup when {@code enabled=true}.
     */
    private String secret = "";

    /**
     * Ant path patterns (matched against the request URI) that require the internal token.
     * Defaults cover auth-identity's internal-only surface: the RBAC resolution + management API
     * ({@code /v1/rbac/**}), the approval-decision API ({@code /v1/approvals/**}), and the
     * machine-credential surface ({@code /internal/**} — API-key issuance + HMAC verify, called
     * server-to-server by config-registry / the gateway). The {@code /internal/} prefix is the
     * platform convention for "never publicly routed", so gating it wholesale is intentional.
     */
    private List<String> pathPatterns = List.of("/v1/rbac/**", "/v1/approvals/**", "/internal/**");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }

    public List<String> getPathPatterns() { return pathPatterns; }
    public void setPathPatterns(List<String> pathPatterns) { this.pathPatterns = pathPatterns; }
}
