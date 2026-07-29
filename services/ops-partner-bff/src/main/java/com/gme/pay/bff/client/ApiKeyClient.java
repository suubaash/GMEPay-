package com.gme.pay.bff.client;

import java.time.Instant;
import java.util.List;

/**
 * Read-only view of the per-partner API key registry, owned by {@code auth-identity}
 * (ADR-011 — it owns key lifecycle).
 *
 * <p>API keys are HMAC credentials partners use to authenticate against the public
 * {@code api-gateway}. Only the key's non-secret display prefix is ever returned — the
 * secret is shown once at creation time and never again (SEC-09 §4), because the store
 * keeps only a salted PBKDF2 hash.
 *
 * <p>{@link com.gme.pay.bff.client.rest.RestApiKeyClient} is the real implementation
 * ({@code gmepay.auth-identity.client=rest}); {@link com.gme.pay.bff.client.stub.StubApiKeyClient}
 * is the standalone-boot default and, since gap T1-3, reports NO keys rather than
 * fabricating credential-shaped rows.
 */
public interface ApiKeyClient {

    /**
     * Returns the list of API keys owned by {@code partnerId}, newest-first.
     * Returns an empty list (never null) for unknown partners.
     */
    List<ApiKeyView> listForPartner(String partnerId);

    /**
     * Display shape for a single API key. Every field is either real upstream data or
     * honestly {@code null}/empty — nothing here is synthesised (gap T1-3).
     *
     * @param keyId       public key identifier ({@code api_keys.api_key}); not secret.
     * @param name        human label. <b>Always {@code null} from the real registry</b> —
     *                    {@code api_keys} (V002) has no name column and no other service owns
     *                    one. The UI renders an em dash.
     * @param prefix      non-secret display prefix (first 12 chars of the key id) — safe to render.
     * @param scopes      granted OAuth-style scopes. <b>Always empty from the real registry</b> —
     *                    per-key scopes are not modelled on an {@code api_keys} row; authorization
     *                    is decided by the principal's RBAC grants, not by the key.
     * @param createdAt   real issuance instant from the {@code api_keys} row.
     * @param lastUsedAt  last successful authentication. <b>Always {@code null} from the real
     *                    registry</b> — {@code api_keys} has no {@code last_used_at} column, so
     *                    nothing records it.
     * @param status      real lifecycle state from {@code api_keys.status}:
     *                    {@code ACTIVE | PENDING_EXPIRY | REVOKED}.
     * @param environment {@code SANDBOX | PRODUCTION} roster the credential belongs to — real, and
     *                    the field that actually distinguishes a test key from a live one.
     * @param expiresAt   real expiry instant; {@code null} = no expiry configured.
     */
    record ApiKeyView(
            String keyId,
            String name,
            String prefix,
            List<String> scopes,
            Instant createdAt,
            Instant lastUsedAt,
            String status,
            String environment,
            Instant expiresAt
    ) {}
}
