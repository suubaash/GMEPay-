package com.gme.pay.auth.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The platform JWT <strong>key set</strong> — one key that signs, plus zero or more previously
 * active keys that are still accepted for verification until the tokens they signed expire
 * (gap register <b>T0-6</b>, the rotation half).
 *
 * <h2>Why a set and not a value</h2>
 *
 * <p>The platform capability token is <b>HS256, i.e. symmetric</b>: the signing key <em>is</em> the
 * verification key, so whoever holds it can mint a token for any subject with any claims. Before
 * this class there was exactly one such value, un-versioned and unnamed. Two consequences, both of
 * them the CISO objection recorded against T0-6:
 *
 * <ul>
 *   <li><b>No scheduled rotation was possible at all.</b> Changing the key is instantaneous and
 *       total — the instant the new value reaches the process, every token minted under the old one
 *       stops verifying. There is no way to phase a new key in.</li>
 *   <li><b>A suspected compromise had no graduated response.</b> The only lever was that same hard
 *       cutover, which invalidates every live session. So the operational cost of reacting to a
 *       <em>suspicion</em> was an outage, which in practice means suspicions do not get acted
 *       on.</li>
 * </ul>
 *
 * <p>With a key set, rotation becomes: promote a new key to active, keep the outgoing one accepted
 * for at least one maximum token TTL, then retire it. Tokens minted before the switch keep working
 * until they expire on their own, and a compromise response is a deliberate choice between the
 * graceful path and an explicit hard cutover.
 *
 * <h2>Key identity: the {@code kid} is DERIVED, never operator-chosen</h2>
 *
 * <p>{@link #kidFor(String)} is a truncated SHA-256 over a domain-separated copy of the secret,
 * rendered as {@code gmek_<16 hex>}. That is deliberate, and it is the one design decision here
 * worth arguing:
 *
 * <ul>
 *   <li>An operator-chosen {@code kid} introduces a failure mode that has no upside — a label
 *       pointing at the wrong secret. Every token then carries a {@code kid} that selects a key
 *       which cannot verify it, and the symptom (mass {@code INVALID_TOKEN}) looks identical to a
 *       forgery wave. A derived id cannot be mismatched: the id <em>is</em> a function of the
 *       material.</li>
 *   <li>It shrinks the rotation config to the thing that actually matters. An operator supplies
 *       secrets and dates; they never have to keep a second list of names in sync.</li>
 *   <li>It leaks nothing. The digest is one-way and truncated, and the value is published in the
 *       header of every token the platform mints anyway.</li>
 *   <li>It makes the same key produce the same {@code kid} in every replica and every environment
 *       with no coordination, so "did the rotation take on all pods" is answerable by comparing a
 *       short string.</li>
 * </ul>
 *
 * <p>The cost is that a {@code kid} is not human-meaningful. That is paid back by
 * {@link #report(Duration, Instant)}, which pairs each id with its demotion date and the instant it
 * becomes safe to remove.
 *
 * <h2>What this class does NOT cover</h2>
 *
 * <p>Only the platform JWT minted by {@code auth-identity}. The Keycloak/OIDC tokens that
 * api-gateway and ops-partner-bff validate are a different trust path entirely (RS256, JWKS,
 * rotated by Keycloak, already versioned by {@code kid} at the IdP). The shared internal-auth token
 * ({@code X-Gme-Internal}), the RBAC claim-stamping secret and the webhook HKDF derivation root are
 * separate secrets with separate lifecycles — see the register entry.
 *
 * <p>Immutable; no Spring dependency, so it is unit-testable without a context.
 */
public final class JwtKeySet {

    /** Prefix on every derived {@code kid}, so a GME key id is recognisable in a decoded header. */
    public static final String KID_PREFIX = "gmek_";

    /**
     * Domain separator folded into the digest. Keeps the {@code kid} from colliding with any other
     * SHA-256-of-the-secret that might exist elsewhere (a fingerprint in an audit row, say), so the
     * published key id can never be compared against, or substituted for, some other digest of the
     * same material.
     */
    private static final String KID_DOMAIN = "gmepay-jwt-kid-v1:";

    /** Hex characters of digest retained. 16 hex = 64 bits — far beyond collision risk for a set of &lt;10 keys. */
    private static final int KID_HEX_CHARS = 16;

    /**
     * Separator between the secret and its demotion timestamp in {@code gme.auth.jwt.previous-keys}.
     * Split on the LAST occurrence, so a secret that itself contains {@code @} still parses.
     */
    private static final char DEMOTED_AT_SEPARATOR = '@';

    /** Entry separator in {@code gme.auth.jwt.previous-keys}: {@code ;} or a newline. */
    private static final String ENTRY_SEPARATORS = "[;\\r\\n]";

    /**
     * One key in the set.
     *
     * @param kid        derived key id ({@link #kidFor(String)})
     * @param secret     raw HS256 secret
     * @param demotedAt  when the key stopped signing; {@code null} for the active key
     */
    public record Key(String kid, String secret, Instant demotedAt) {

        public Key {
            if (kid == null || kid.isBlank()) {
                throw new IllegalArgumentException("kid must not be blank");
            }
            if (secret == null || secret.isBlank()) {
                throw new IllegalArgumentException("secret must not be blank");
            }
        }

        public boolean isActive() {
            return demotedAt == null;
        }

        public byte[] secretBytes() {
            return secret.getBytes(StandardCharsets.UTF_8);
        }

        /**
         * The instant after which no token this key signed can still be live, and the key can
         * therefore be removed from configuration without invalidating anything.
         *
         * @return {@code null} for the active key (it is still minting, so it is never removable)
         */
        public Instant safeToRemoveAfter(Duration maxTokenTtl) {
            return demotedAt == null ? null : demotedAt.plus(maxTokenTtl);
        }
    }

    /** A key plus its retirement arithmetic, for the startup log and the operator endpoint. */
    public record KeyStatus(String kid,
                            boolean active,
                            Instant demotedAt,
                            Instant safeToRemoveAfter,
                            boolean safeToRemoveNow,
                            boolean overdueForRemoval) {}

    private final Key active;
    private final List<Key> previous;
    private final Map<String, Key> byKid;

    private JwtKeySet(Key active, List<Key> previous) {
        this.active = active;
        this.previous = List.copyOf(previous);
        Map<String, Key> index = new LinkedHashMap<>();
        index.put(active.kid(), active);
        for (Key key : this.previous) {
            // Two entries with the same kid means the same secret listed twice. Rejecting rather
            // than de-duplicating: the operator believes they configured two keys and one of the
            // two demotion timestamps is silently being ignored, which is exactly the input that
            // makes the retirement arithmetic below wrong.
            if (index.putIfAbsent(key.kid(), key) != null) {
                throw new IllegalArgumentException(
                        "duplicate JWT key " + key.kid() + " — the same secret appears more than "
                        + "once in the key set (as the active key and/or twice in "
                        + "gme.auth.jwt.previous-keys)");
            }
        }
        this.byKid = Collections.unmodifiableMap(index);
    }

    /** A set with a single active key and no accepted predecessors (a first deployment). */
    public static JwtKeySet active(String activeSecret) {
        return of(activeSecret, List.of());
    }

    /**
     * Builds the set.
     *
     * @param activeSecret the key that signs — must be present. A set with no active key cannot
     *                     mint, and a service that cannot mint must not start (T0-6).
     * @param previous     previously active keys, still accepted for verification
     */
    public static JwtKeySet of(String activeSecret, List<Key> previous) {
        if (activeSecret == null || activeSecret.isBlank()) {
            throw new IllegalArgumentException(
                    "the JWT key set has no active key — gme.auth.jwt.signing-secret is blank");
        }
        return new JwtKeySet(new Key(kidFor(activeSecret), activeSecret, null),
                             previous == null ? List.of() : previous);
    }

    /** A previously active key, still accepted for verification. */
    public static Key retiredKey(String secret, Instant demotedAt) {
        if (demotedAt == null) {
            throw new IllegalArgumentException("a previously active key must declare demotedAt");
        }
        return new Key(kidFor(secret), secret, demotedAt);
    }

    /**
     * Derives the {@code kid} for a secret. Stable across processes, replicas and restarts;
     * one-way; carries no key material.
     */
    public static String kidFor(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("cannot derive a kid for a blank secret");
        }
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256")
                    .digest((KID_DOMAIN + secret).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        StringBuilder hex = new StringBuilder(KID_HEX_CHARS);
        for (int i = 0; hex.length() < KID_HEX_CHARS; i++) {
            hex.append(String.format("%02x", digest[i]));
        }
        return KID_PREFIX + hex.substring(0, KID_HEX_CHARS);
    }

    /**
     * Parses {@code gme.auth.jwt.previous-keys}.
     *
     * <p>Format — {@code <secret>@<ISO-8601 instant>}, entries separated by {@code ;} or newlines:
     *
     * <pre>{@code
     * GME_AUTH_JWT_PREVIOUS_KEYS="e1f0…c3@2026-07-28T09:00:00Z;9ab7…21@2026-06-14T09:00:00Z"
     * }</pre>
     *
     * <p>The timestamp is <b>mandatory</b>, and that is the load-bearing part of the format rather
     * than decoration: it is when the key stopped signing, and therefore the only input from which
     * "when does this key become safe to delete" can be computed. An operator who cannot say when a
     * key was demoted cannot reason about the overlap window, so the parse fails instead of
     * guessing.
     *
     * @throws IllegalArgumentException on any malformed entry — never a partial parse. Silently
     *         dropping an unparseable entry would drop a key that live tokens still reference.
     */
    public static List<Key> parsePreviousKeys(String spec) {
        List<Key> keys = new ArrayList<>();
        if (spec == null || spec.isBlank()) {
            return keys;
        }
        for (String rawEntry : spec.split(ENTRY_SEPARATORS)) {
            String entry = rawEntry.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int at = entry.lastIndexOf(DEMOTED_AT_SEPARATOR);
            if (at < 0) {
                throw new IllegalArgumentException(
                        "gme.auth.jwt.previous-keys entry #" + (keys.size() + 1) + " is missing its "
                        + "'@<demoted-at>' timestamp. Each previously active key must be written as "
                        + "<secret>@<ISO-8601 instant>, e.g. "
                        + "<secret>@2026-07-28T09:00:00Z — the timestamp is what determines when the "
                        + "key can safely be removed");
            }
            String secret = entry.substring(0, at).trim();
            String demotedAtText = entry.substring(at + 1).trim();
            if (secret.isEmpty()) {
                throw new IllegalArgumentException(
                        "gme.auth.jwt.previous-keys entry #" + (keys.size() + 1)
                        + " has an empty secret");
            }
            Instant demotedAt;
            try {
                demotedAt = Instant.parse(demotedAtText);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException(
                        "gme.auth.jwt.previous-keys entry #" + (keys.size() + 1)
                        + " has an unparseable demoted-at timestamp '" + demotedAtText
                        + "' — expected an ISO-8601 instant such as 2026-07-28T09:00:00Z", e);
            }
            keys.add(retiredKey(secret, demotedAt));
        }
        return keys;
    }

    /** The key that signs. Never {@code null}. */
    public Key activeKey() {
        return active;
    }

    /** Previously active keys, still accepted for verification. */
    public List<Key> previousKeys() {
        return previous;
    }

    /** Active key first, then the previously active ones. */
    public List<Key> allKeys() {
        List<Key> all = new ArrayList<>(previous.size() + 1);
        all.add(active);
        all.addAll(previous);
        return List.copyOf(all);
    }

    /**
     * Selects the key a token's {@code kid} names.
     *
     * <p>Deliberately an exact lookup with <b>no fallback</b>. Trying every key in turn on an
     * unrecognised {@code kid} would (a) make the {@code kid} decorative, so a retired key could
     * still be honoured through a token that names something else, and (b) turn each rejection into
     * N HMAC computations driven by an unauthenticated caller.
     */
    public Optional<Key> find(String kid) {
        if (kid == null || kid.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKid.get(kid));
    }

    public int size() {
        return byKid.size();
    }

    /**
     * Per-key retirement status — what the startup log prints and what
     * {@code GET /internal/auth/token/keys} returns.
     *
     * @param maxTokenTtl the longest TTL this service will mint
     *                    ({@code gme.auth.jwt.max-token-ttl-seconds}); a key's tokens cannot
     *                    outlive its demotion by more than this
     */
    public List<KeyStatus> report(Duration maxTokenTtl, Instant now) {
        List<KeyStatus> statuses = new ArrayList<>();
        statuses.add(new KeyStatus(active.kid(), true, null, null, false, false));
        for (Key key : previous) {
            Instant safeAfter = key.safeToRemoveAfter(maxTokenTtl);
            boolean safeNow = now.isAfter(safeAfter);
            // "Overdue" is deliberately a full extra TTL past safe, not one second past: an
            // operator who retires promptly should never see the warning, but a key left in the
            // accepted list for a whole extra window is one more copy of live signing material
            // sitting in a deployment manifest for no benefit.
            boolean overdue = now.isAfter(safeAfter.plus(maxTokenTtl));
            statuses.add(new KeyStatus(key.kid(), false, key.demotedAt(), safeAfter, safeNow, overdue));
        }
        return List.copyOf(statuses);
    }
}
