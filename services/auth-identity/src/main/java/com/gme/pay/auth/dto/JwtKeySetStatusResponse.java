package com.gme.pay.auth.dto;

import java.time.Instant;
import java.util.List;

/**
 * Read-only view of the JWT key set this process is running with — the answer to
 * "did the rotation take, on every replica?" and "when can I delete the old key?" (T0-6).
 *
 * <p>Returned by {@code GET /internal/auth/token/keys}, behind the {@code X-Gme-Internal} gate like
 * every other route on this service.
 *
 * <p><b>It carries no key material.</b> A {@code kid} is a truncated, domain-separated SHA-256 of
 * the secret and is already published in the header of every token the platform mints, so exposing
 * it here adds no disclosure. What it adds is a way to confirm a rotation reached every pod without
 * comparing secrets: same {@code activeKid} everywhere means the same signing key everywhere.
 *
 * @param activeKid  the {@code kid} stamped into tokens minted right now
 * @param maxTokenTtlSeconds the longest TTL this service will mint — the width of the overlap
 *                           window a rotation must leave open
 * @param keys       the active key first, then each still-accepted predecessor
 */
public record JwtKeySetStatusResponse(String activeKid,
                                      long maxTokenTtlSeconds,
                                      List<KeyStatus> keys) {

    /**
     * @param kid               derived key id
     * @param role              {@code ACTIVE} (signs and verifies) or {@code ACCEPTED}
     *                          (verifies only — a previously active key inside its overlap window)
     * @param demotedAt         when the key stopped signing; {@code null} for the active key
     * @param safeToRemoveAfter {@code demotedAt + maxTokenTtlSeconds} — after this instant no token
     *                          the key signed can still be live, so removing it from configuration
     *                          invalidates nothing. {@code null} for the active key
     * @param safeToRemoveNow   whether that instant has passed
     * @param overdueForRemoval kept a full extra TTL beyond safe — an unnecessary extra copy of
     *                          live signing material; the startup log WARNs about these
     */
    public record KeyStatus(String kid,
                            String role,
                            Instant demotedAt,
                            Instant safeToRemoveAfter,
                            boolean safeToRemoveNow,
                            boolean overdueForRemoval) {

        public static final String ROLE_ACTIVE   = "ACTIVE";
        public static final String ROLE_ACCEPTED = "ACCEPTED";
    }
}
