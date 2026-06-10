package com.gme.pay.bff.client;

import java.time.Instant;
import java.util.List;

/**
 * Read-only view of the per-partner API key registry. Production
 * implementation calls {@code auth-identity} (or {@code config-registry}
 * depending on final ownership); Phase-1 default is an in-memory stub so the
 * Partner Portal API Keys page can render.
 *
 * <p>API keys are HMAC credentials used by partners to authenticate against
 * the public {@code api-gateway}. Each partner has a {@code PRIMARY} key in
 * active use and a {@code ROTATING} key for zero-downtime rotation. Only the
 * key's prefix is ever returned by the API — the secret is shown once at
 * creation time and never again.
 */
public interface ApiKeyClient {

    /**
     * Returns the list of API keys owned by {@code partnerId}, newest-first.
     * Returns an empty list (never null) for unknown partners.
     */
    List<ApiKeyView> listForPartner(String partnerId);

    /**
     * Display shape for a single API key. {@code prefix} is the first 12
     * characters of the key (e.g. {@code "gpk_live_ab12"}) — safe to render.
     * {@code scopes} is the set of granted OAuth-style scopes (e.g.
     * {@code payments:write}, {@code reports:read}). {@code status} is one of
     * {@code "PRIMARY"}, {@code "ROTATING"}, {@code "REVOKED"}.
     */
    record ApiKeyView(
            String keyId,
            String name,
            String prefix,
            List<String> scopes,
            Instant createdAt,
            Instant lastUsedAt,
            String status
    ) {}
}
