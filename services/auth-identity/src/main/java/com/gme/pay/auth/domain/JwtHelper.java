package com.gme.pay.auth.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Stateless JWT issue / verify helper for <strong>internal service-to-service
 * tokens</strong> (e.g. signed callbacks, short-lived internal capability tokens).
 *
 * <p>Per ADR-011 this helper is NOT used to issue human operator session tokens —
 * operator sessions are owned by Keycloak, which signs its own JWTs using RS256
 * and rotates JWKS keys. The api-gateway validates Keycloak-issued operator JWTs
 * via {@code spring-security-oauth2-resource-server}, not via this helper.
 *
 * <p>Algorithm: HS256 (HMAC-SHA256 signed compact JWT).
 * Implemented using only JDK classes — no external JWT library required.
 *
 * <p>Format: {@code Base64Url(header) + "." + Base64Url(payload) + "." + Base64Url(signature)}
 * where {@code header  = {"alg":"HS256","typ":"JWT","kid":"gmek_…"}}
 *       {@code payload = {"sub":…,"iat":…,"exp":…,"jti":…, ...extraClaims}}.
 *
 * <h2>Key versioning (T0-6)</h2>
 *
 * <p>This helper signs with the <em>active</em> key of a {@link JwtKeySet} and stamps that key's
 * {@code kid} into the header. Verification selects the key by {@code kid} and uses <b>only</b>
 * that key. Three properties follow, and each of them was absent before:
 *
 * <ul>
 *   <li>a previously active key can stay accepted for verification while the tokens it signed
 *       expire, so rotation is a window rather than a cutover;</li>
 *   <li>an <b>unknown {@code kid} is rejected outright</b> ({@link Outcome#UNKNOWN_KID}) — never by
 *       trying the other keys. A fallback would make the {@code kid} advisory, so a retired key
 *       would still be honoured by any token that named a live one, and it would let an
 *       unauthenticated caller cost the service one HMAC per configured key per bad token;</li>
 *   <li>a token carrying <b>no {@code kid} at all is rejected</b>. Pinned decision, see
 *       {@link #verifyDetailed(String)}.</li>
 * </ul>
 *
 * <p>The {@code alg} header is checked explicitly. It was not exploitable before (the signature has
 * to match a recomputed HMAC regardless of what the header claims), but with the header now being
 * parsed for the {@code kid} it is worth refusing anything that is not {@code HS256} at the point
 * of parse rather than relying on the HMAC to fail.
 *
 * <p>This class has no Spring dependencies — it can be unit-tested without a context.
 */
public final class JwtHelper {

    private static final String ALG = "HS256";

    private final JwtKeySet keySet;
    private final long      accessTokenTtlSeconds;
    /** Pre-rendered header for the active key — one Base64 encode per process, not per token. */
    private final String    activeHeaderB64;

    /**
     * @param keySet                the active signing key plus any still-accepted predecessors
     * @param accessTokenTtlSeconds lifetime of issued access tokens in seconds
     */
    public JwtHelper(JwtKeySet keySet, long accessTokenTtlSeconds) {
        this.keySet                = keySet;
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
        this.activeHeaderB64       = base64url(header(keySet.activeKey().kid())
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Single-key convenience: a set consisting of exactly one active key and no predecessors. The
     * {@code kid} is still derived and still stamped — there is no un-versioned mode.
     *
     * @param signingSecret       raw JWT signing secret (at least 32 ASCII chars for HS256 security)
     * @param accessTokenTtlSeconds lifetime of issued access tokens in seconds
     */
    public JwtHelper(String signingSecret, long accessTokenTtlSeconds) {
        this(JwtKeySet.active(signingSecret), accessTokenTtlSeconds);
    }

    /** The key set this helper signs and verifies with. */
    public JwtKeySet keySet() {
        return keySet;
    }

    /** {@code kid} stamped into every token this helper mints right now. */
    public String activeKid() {
        return keySet.activeKey().kid();
    }

    private static String header(String kid) {
        return "{\"alg\":\"" + ALG + "\",\"typ\":\"JWT\",\"kid\":" + jsonString(kid) + "}";
    }

    /**
     * Issues a signed HS256 JWT.
     *
     * @param subject   principal identifier (e.g. hub_user.id or partner_portal_user.id)
     * @param extraClaims additional claims (role_code, permissions, partner_id, etc.)
     * @return compact serialized JWT string
     */
    public String issue(String subject, Map<String, Object> extraClaims) {
        return issue(subject, extraClaims, accessTokenTtlSeconds);
    }

    /**
     * Issues a signed HS256 JWT with an explicit TTL (overriding the default).
     *
     * @param subject     principal identifier
     * @param extraClaims additional claims
     * @param ttlSeconds  token lifetime in seconds (must be positive)
     * @return compact serialized JWT string
     */
    public String issue(String subject, Map<String, Object> extraClaims, long ttlSeconds) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be null or blank");
        }
        long now = Instant.now().getEpochSecond();
        long exp = now + ttlSeconds;
        String jti = UUID.randomUUID().toString();

        StringBuilder json = new StringBuilder("{");
        json.append("\"sub\":").append(jsonString(subject)).append(",");
        json.append("\"jti\":").append(jsonString(jti)).append(",");
        json.append("\"iat\":").append(now).append(",");
        json.append("\"exp\":").append(exp);
        Map<String, Object> claims = extraClaims == null ? Map.of() : extraClaims;
        for (Map.Entry<String, Object> e : claims.entrySet()) {
            json.append(",").append(jsonString(e.getKey())).append(":")
                .append(toJsonValue(e.getValue()));
        }
        json.append("}");

        String payloadB64 = base64url(json.toString().getBytes(StandardCharsets.UTF_8));
        String signingInput = activeHeaderB64 + "." + payloadB64;
        String signatureB64 = base64url(hmacSha256(signingInput.getBytes(StandardCharsets.UTF_8),
                                                   keySet.activeKey().secretBytes()));

        return signingInput + "." + signatureB64;
    }

    /**
     * Verifies and parses a compact JWT string.
     *
     * @param token compact JWT
     * @return {@link JwtClaims} if the token has a valid signature and is not expired,
     *         or {@code null} if invalid / expired / malformed
     */
    public JwtClaims verify(String token) {
        VerificationResult result = verifyDetailed(token);
        return result.outcome() == Outcome.VALID ? result.claims() : null;
    }

    /**
     * Verifies a token and reports <em>why</em> it failed, so callers can map
     * the outcome to a precise error code ({@code INVALID_TOKEN} vs
     * {@code EXPIRED_TOKEN}). A valid token yields {@link Outcome#valid}.
     *
     * <p>This complements {@link #verify(String)} (which collapses all failures
     * to {@code null}); both share the same signature/expiry logic.
     *
     * @param token compact JWT
     * @return a {@link VerificationResult} — never {@code null}.
     */
    public VerificationResult verifyDetailed(String token) {
        if (token == null || token.isBlank()) {
            return VerificationResult.invalid();
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return VerificationResult.invalid();
        }

        // ── header: pick the key BEFORE computing any HMAC ────────────────────
        String headerJson;
        try {
            headerJson = new String(base64urlDecode(parts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return VerificationResult.invalid();
        }
        if (!ALG.equals(extractString(headerJson, "alg"))) {
            return VerificationResult.invalid();
        }
        String kid = extractString(headerJson, "kid");
        if (kid == null || kid.isBlank()) {
            // PINNED DECISION (T0-6): a token with no `kid` is INVALID, not "try the active key".
            //
            // Everything this platform mints carries one, so an un-kidded token is either (a) a
            // token issued by a build older than this change, or (b) something this service did not
            // mint. Case (a) is bounded and self-clearing: those tokens are at most
            // gme.auth.jwt.max-token-ttl-seconds old, so the effect of the upgrade is one
            // maximum-TTL window in which pre-upgrade tokens are refused, identical to a key
            // rotation with no overlap and documented as such in the runbook. Case (b) is precisely
            // what must not be accepted. Falling back to the active key to be kind to (a) would
            // permanently keep open an un-versioned verification path, which is the thing T0-6
            // exists to close — a compatibility switch here would become the switch nobody turns
            // off, so there deliberately is not one.
            return VerificationResult.invalid();
        }
        JwtKeySet.Key key = keySet.find(kid).orElse(null);
        if (key == null) {
            // No fallback to the other keys — see the class javadoc. Surfaced as its own outcome
            // because it is operationally distinct from a bad signature: a burst of UNKNOWN_KID is
            // the signature of a key retired before its tokens expired, whereas a burst of INVALID
            // is a forgery attempt.
            return VerificationResult.unknownKid(kid);
        }

        String signingInput = parts[0] + "." + parts[1];
        byte[] actualSig = hmacSha256(signingInput.getBytes(StandardCharsets.UTF_8), key.secretBytes());
        byte[] expectedSig;
        try {
            expectedSig = base64urlDecode(parts[2]);
        } catch (IllegalArgumentException e) {
            return VerificationResult.invalid();
        }
        // Constant-time comparison to prevent a timing side-channel.
        if (!java.security.MessageDigest.isEqual(expectedSig, actualSig)) {
            return VerificationResult.invalid();
        }

        try {
            String payloadJson = new String(base64urlDecode(parts[1]), StandardCharsets.UTF_8);
            long exp = extractLong(payloadJson, "exp");
            String sub = extractString(payloadJson, "sub");
            String jti = extractString(payloadJson, "jti");
            JwtClaims claims = new JwtClaims(sub, jti, exp, payloadJson);
            if (Instant.now().getEpochSecond() > exp) {
                return VerificationResult.expired(claims, kid);
            }
            return VerificationResult.valid(claims, kid);
        } catch (Exception e) {
            return VerificationResult.invalid();
        }
    }

    /** Outcome category for {@link #verifyDetailed(String)}. */
    public enum Outcome {
        VALID,
        EXPIRED,
        /** The header named a {@code kid} this service does not hold — typically a key retired too early. */
        UNKNOWN_KID,
        INVALID
    }

    /**
     * Result of {@link #verifyDetailed(String)}. {@code claims} is populated for
     * {@link Outcome#VALID} and {@link Outcome#EXPIRED} (signature was good), and {@code null}
     * otherwise. {@code kid} is populated whenever the header carried one, including for
     * {@link Outcome#UNKNOWN_KID} — that value is caller-supplied and must be treated as untrusted
     * input (it is safe to log, never to act on).
     */
    public record VerificationResult(Outcome outcome, JwtClaims claims, String kid) {
        static VerificationResult valid(JwtClaims c, String kid) {
            return new VerificationResult(Outcome.VALID, c, kid);
        }
        static VerificationResult expired(JwtClaims c, String kid) {
            return new VerificationResult(Outcome.EXPIRED, c, kid);
        }
        static VerificationResult unknownKid(String kid) {
            return new VerificationResult(Outcome.UNKNOWN_KID, null, kid);
        }
        static VerificationResult invalid() {
            return new VerificationResult(Outcome.INVALID, null, null);
        }
        public boolean isValid() { return outcome == Outcome.VALID; }
    }

    /** Parsed claims returned by {@link #verify(String)}. */
    public record JwtClaims(String subject, String jti, long exp, String rawJson) {}

    // ── private helpers ───────────────────────────────────────────────────────

    private static byte[] hmacSha256(byte[] data, byte[] keyBytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String base64url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static byte[] base64urlDecode(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String toJsonValue(Object v) {
        if (v instanceof String s) return jsonString(s);
        if (v instanceof Number || v instanceof Boolean) return String.valueOf(v);
        return jsonString(String.valueOf(v));
    }

    /** Very small JSON field extractor — no external dependency. */
    private static String extractString(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    private static long extractLong(String json, String key) {
        String search = "\"" + key + "\":";
        int start = json.indexOf(search);
        if (start < 0) throw new IllegalArgumentException("Key not found: " + key);
        start += search.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }
}
