package com.gme.pay.gateway.filter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Pure-domain helper that implements HMAC-SHA256 request signature verification
 * as specified in API-05 section 3.2.
 *
 * <p>Canonical string format:
 * <pre>
 * HTTP_METHOD\n
 * PATH_WITH_QUERY\n
 * X-Timestamp value\n
 * SHA256_HEX_OF_BODY
 * </pre>
 *
 * <p>This class has no Spring or Reactor dependency, making it trivially unit-testable.
 */
public final class HmacSignatureVerifier {

    private static final String HMAC_ALGO = "HmacSHA256";

    private HmacSignatureVerifier() {}

    /**
     * Build the canonical string that the partner must sign.
     *
     * @param method        HTTP method in upper-case (e.g. {@code "POST"})
     * @param pathWithQuery full request path including query string (e.g. {@code "/v1/rates?foo=1"})
     * @param timestamp     value of the {@code X-Timestamp} header
     * @param bodyBytes     raw request body bytes; pass {@code new byte[0]} for body-less requests
     * @return canonical string (UTF-8)
     */
    public static String buildCanonicalString(
            String method,
            String pathWithQuery,
            String timestamp,
            byte[] bodyBytes) {

        String bodyHash = sha256Hex(bodyBytes);
        return method + "\n" + pathWithQuery + "\n" + timestamp + "\n" + bodyHash;
    }

    /**
     * Compute the HMAC-SHA256 of {@code canonical} with {@code secret} and return as lower-case hex.
     */
    public static String computeHmac(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            SecretKeySpec keySpec = new SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGO);
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(rawHmac);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /**
     * Constant-time comparison of two hex-encoded HMAC values.
     * Uses {@link MessageDigest#isEqual} to prevent timing oracles.
     *
     * @return {@code true} iff {@code expected} and {@code actual} are equal
     */
    public static boolean verifySignature(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes   = actual.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, actualBytes);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Compute SHA-256 of {@code data} and return as lower-case hex.
     * An empty array produces the well-known SHA-256 of the empty string:
     * {@code e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855}.
     */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return bytesToHex(md.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
