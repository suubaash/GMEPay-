package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.ApiKeyClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Phase-1 in-memory stub of {@link ApiKeyClient}. Returns 2 deterministic
 * keys per partner — one {@code PRIMARY} and one {@code ROTATING} — with
 * realistic-looking prefixes (e.g. {@code gpk_live_...}) so the Partner Portal
 * API Keys page renders without booting auth-identity.
 *
 * <p>The keys are seeded per partner id (deterministic hash of the partner id
 * gates the prefix) so the same partner always sees the same fake keys
 * across page reloads.
 */
@Component
public class StubApiKeyClient implements ApiKeyClient {

    private static final List<String> DEFAULT_SCOPES = List.of(
            "payments:write", "reports:read");

    @Override
    public List<ApiKeyView> listForPartner(String partnerId) {
        String safe = partnerId == null ? "anon" : partnerId;
        // Deterministic 4-char prefix derived from the partner id so the
        // primary and rotating keys differ but both are stable for a given
        // partner.
        String suffix = shortHash(safe);

        ApiKeyView primary = new ApiKeyView(
                "key_primary_" + safe,
                "Primary",
                "gpk_live_" + suffix + "_p",
                DEFAULT_SCOPES,
                Instant.parse("2026-01-15T10:00:00Z"),
                Instant.parse("2026-06-10T08:32:14Z"),
                "PRIMARY");
        ApiKeyView rotating = new ApiKeyView(
                "key_rotating_" + safe,
                "Rotating",
                "gpk_live_" + suffix + "_r",
                DEFAULT_SCOPES,
                Instant.parse("2026-05-20T09:30:00Z"),
                null,
                "ROTATING");

        return List.of(primary, rotating);
    }

    /** Tiny 4-char hex from a stable hash so prefixes are deterministic. */
    private static String shortHash(String value) {
        int h = value.hashCode() & 0xFFFF;
        return String.format("%04x", h);
    }
}
