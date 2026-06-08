package com.gme.pay.auth.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Pure-Java HMAC-SHA256 canonical request-signature utility.
 *
 * Canonical string format (SEC-09 §3.3, API-05 §3.2):
 *   HTTP_METHOD + "\n" + PATH_WITH_QUERY + "\n" + X-Timestamp + "\n" + SHA256_HEX_OF_BODY
 *
 * No Spring dependencies; safe to use from api-gateway or any other JVM module.
 */
public final class HmacSignatureVerifier {

    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final String SHA_256     = "SHA-256";

    private HmacSignatureVerifier() {}

    /**
     * Computes the hex-encoded SHA-256 digest of the given body bytes.
     * Empty body (GET) yields e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855.
     */
    public static String computeBodyHash(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance(SHA_256);
            return hexEncode(md.digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Builds the canonical request string from four parts joined by a single newline.
     *
     * @param method        HTTP method, e.g. "POST"
     * @param pathWithQuery full path including query string, e.g. "/v1/payments?foo=bar"
     * @param timestamp     ISO-8601 UTC millisecond-precision timestamp from X-Timestamp header
     * @param bodyHash      hex-encoded SHA-256 body hash from {@link #computeBodyHash(byte[])}
     */
    public static String buildCanonicalString(String method, String pathWithQuery,
                                               String timestamp, String bodyHash) {
        return method + "\n" + pathWithQuery + "\n" + timestamp + "\n" + bodyHash;
    }

    /**
     * Computes HMAC-SHA256 of the canonical string using the given secret, hex-encoded lowercase.
     */
    public static String computeSignature(String canonicalString, String hmacSecret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec keySpec = new SecretKeySpec(
                    hmacSecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(keySpec);
            return hexEncode(mac.doFinal(canonicalString.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 not available or invalid key", e);
        }
    }

    /**
     * Constant-time comparison of the expected signature against a freshly computed one.
     * Uses {@link MessageDigest#isEqual} to prevent timing side-channel attacks.
     *
     * @param providedSignature the hex signature supplied by the caller (X-Signature header)
     * @param canonicalString   the canonical request string for recomputation
     * @param hmacSecret        the partner HMAC secret
     * @return true iff signatures match
     */
    public static boolean verifySignature(String providedSignature, String canonicalString,
                                           String hmacSecret) {
        String expected = computeSignature(canonicalString, hmacSecret);
        // MessageDigest.isEqual provides constant-time comparison — no timing leak
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                providedSignature.getBytes(StandardCharsets.UTF_8));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String hexEncode(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
