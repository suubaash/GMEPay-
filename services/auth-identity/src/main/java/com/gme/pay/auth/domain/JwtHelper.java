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
 * where {@code header  = {"alg":"HS256","typ":"JWT"}}
 *       {@code payload = {"sub":…,"iat":…,"exp":…,"jti":…, ...extraClaims}}.
 *
 * <p>This class has no Spring dependencies — it can be unit-tested without a context.
 */
public final class JwtHelper {

    private static final String HEADER_B64 = base64url("""
            {"alg":"HS256","typ":"JWT"}""".strip().getBytes(StandardCharsets.UTF_8));

    private final byte[] signingKeyBytes;
    private final long   accessTokenTtlSeconds;

    /**
     * @param signingSecret       raw JWT signing secret (at least 32 ASCII chars for HS256 security)
     * @param accessTokenTtlSeconds lifetime of issued access tokens in seconds
     */
    public JwtHelper(String signingSecret, long accessTokenTtlSeconds) {
        this.signingKeyBytes     = signingSecret.getBytes(StandardCharsets.UTF_8);
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }

    /**
     * Issues a signed HS256 JWT.
     *
     * @param subject   principal identifier (e.g. hub_user.id or partner_portal_user.id)
     * @param extraClaims additional claims (role_code, permissions, partner_id, etc.)
     * @return compact serialized JWT string
     */
    public String issue(String subject, Map<String, Object> extraClaims) {
        long now = Instant.now().getEpochSecond();
        long exp = now + accessTokenTtlSeconds;
        String jti = UUID.randomUUID().toString();

        StringBuilder json = new StringBuilder("{");
        json.append("\"sub\":").append(jsonString(subject)).append(",");
        json.append("\"jti\":").append(jsonString(jti)).append(",");
        json.append("\"iat\":").append(now).append(",");
        json.append("\"exp\":").append(exp);
        for (Map.Entry<String, Object> e : extraClaims.entrySet()) {
            json.append(",").append(jsonString(e.getKey())).append(":")
                .append(toJsonValue(e.getValue()));
        }
        json.append("}");

        String payloadB64 = base64url(json.toString().getBytes(StandardCharsets.UTF_8));
        String signingInput = HEADER_B64 + "." + payloadB64;
        String signatureB64 = base64url(hmacSha256(signingInput.getBytes(StandardCharsets.UTF_8)));

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
        if (token == null || token.isBlank()) return null;
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) return null;

        String signingInput = parts[0] + "." + parts[1];
        byte[] expectedSig  = base64urlDecode(parts[2]);
        byte[] actualSig    = hmacSha256(signingInput.getBytes(StandardCharsets.UTF_8));

        // Constant-time comparison to prevent timing side-channel
        if (!java.security.MessageDigest.isEqual(expectedSig, actualSig)) return null;

        // Decode payload and extract exp / sub
        try {
            String payloadJson = new String(base64urlDecode(parts[1]), StandardCharsets.UTF_8);
            long exp = extractLong(payloadJson, "exp");
            if (Instant.now().getEpochSecond() > exp) return null; // expired
            String sub = extractString(payloadJson, "sub");
            String jti = extractString(payloadJson, "jti");
            return new JwtClaims(sub, jti, exp, payloadJson);
        } catch (Exception e) {
            return null;
        }
    }

    /** Parsed claims returned by {@link #verify(String)}. */
    public record JwtClaims(String subject, String jti, long exp, String rawJson) {}

    // ── private helpers ───────────────────────────────────────────────────────

    private byte[] hmacSha256(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKeyBytes, "HmacSHA256"));
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
