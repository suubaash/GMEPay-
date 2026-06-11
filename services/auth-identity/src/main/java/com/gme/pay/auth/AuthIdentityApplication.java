package com.gme.pay.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Auth &amp; Identity microservice — <strong>machine credentials only</strong> (ADR-011).
 *
 * <p>Scope:
 * <ul>
 *   <li>Partner machine-to-machine authentication: HMAC-SHA256 request-signature
 *       verification, API-key lookup, replay/nonce protection.</li>
 *   <li>Internal {@code POST /internal/auth/verify} endpoint consumed by api-gateway.</li>
 *   <li>Stewardship of partner credential persistence ({@code principals},
 *       {@code api_keys}, {@code roles}) for machine identities.</li>
 *   <li>Lightweight JWT issue/verify helper ({@link com.gme.pay.auth.domain.JwtHelper})
 *       retained for internal service-to-service tokens — <em>not</em> for human
 *       operator session tokens.</li>
 * </ul>
 *
 * <p><strong>What is explicitly NOT in this service (per ADR-011):</strong>
 * <ul>
 *   <li>Human operator login flow — owned by Keycloak (OIDC/OAuth2). admin-ui and
 *       partner-portal-ui authenticate against Keycloak; api-gateway acts as the
 *       OAuth2 resource server validating Keycloak-issued JWTs.</li>
 *   <li>Password storage, MFA enrolment, operator password reset — all Keycloak.</li>
 *   <li>Operator-facing {@code /auth/login} endpoints — none exist here and none
 *       will be added. Requests to such paths return 404 by design.</li>
 * </ul>
 *
 * <p>This split keeps partner machine authentication (latency-sensitive,
 * deterministic, audit-heavy) cleanly separated from human SSO (Keycloak's
 * core competency: OIDC, MFA, session, federation).
 */
@SpringBootApplication
public class AuthIdentityApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthIdentityApplication.class, args);
    }
}
