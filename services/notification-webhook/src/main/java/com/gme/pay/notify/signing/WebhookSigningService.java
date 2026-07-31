package com.gme.pay.notify.signing;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Signs webhook payloads with HMAC-SHA256 (API-05 §6.3).
 *
 * <p>Signature format: {@code sha256=<lowercase-hex>}
 * <p>The signing secret is never stored in DB; it is fetched from Vault at dispatch time
 * and zeroed from memory after use.
 */
@Service
public class WebhookSigningService {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_PREFIX = "sha256=";

    /**
     * Computes the HMAC-SHA256 signature for the given body bytes and secret.
     *
     * @param bodyBytes UTF-8 bytes of the JSON request body
     * @param secret    plaintext signing secret (will be zeroed by the caller after use)
     * @return signature string in the form {@code sha256=<lowercase-hex>}
     */
    public String sign(byte[] bodyBytes, String secret) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secretBytes, ALGORITHM));
            byte[] digest = mac.doFinal(bodyBytes);
            return SIGNATURE_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        } finally {
            Arrays.fill(secretBytes, (byte) 0);
        }
    }

    /**
     * Builds the {@code X-GME-Webhook-Signature} header value for one or two secrets —
     * gap T5-4 rotation support.
     *
     * <p>With a single secret the wire format is unchanged ({@code sha256=<hex>}). During
     * a rotation overlap window a SECOND value is appended, comma-separated
     * ({@code sha256=<new>,sha256=<old>}), so a partner that still holds only the retired
     * secret keeps verifying successfully while they cut over. A verifier must accept the
     * payload if <b>any</b> listed signature matches (see
     * {@link #verifySignature(String, String, String)}).
     *
     * @param bodyBytes       UTF-8 bytes of the JSON request body
     * @param secret          the endpoint's current secret (required)
     * @param secondarySecret the retired secret during an overlap window, or {@code null}
     */
    public String signatureHeader(byte[] bodyBytes, String secret, String secondarySecret) {
        String primary = sign(bodyBytes, secret);
        if (secondarySecret == null || secondarySecret.isBlank()) {
            return primary;
        }
        return primary + "," + sign(bodyBytes, secondarySecret);
    }

    /**
     * Constant-time verification of a webhook signature header.
     *
     * <p>Accepts a single {@code sha256=<hex>} value or the comma-separated multi-value
     * form emitted during a secret-rotation overlap: the body is authentic if ANY listed
     * signature matches {@code secret}. Every candidate is compared in constant time and
     * no candidate short-circuits the loop for a longer/shorter header.
     *
     * @param rawBody         raw UTF-8 request body
     * @param secret          plaintext signing secret
     * @param signatureHeader the value of X-GME-Webhook-Signature
     * @return {@code true} iff some value in the header is a valid HMAC-SHA256 of
     *         {@code rawBody} under {@code secret}
     */
    public boolean verifySignature(String rawBody, String secret, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        String expected = sign(rawBody.getBytes(StandardCharsets.UTF_8), secret);
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (String candidate : signatureHeader.split(",")) {
            String trimmed = candidate.trim();
            if (!trimmed.startsWith(SIGNATURE_PREFIX)) {
                continue;
            }
            // Constant-time comparison to prevent timing attacks; no early exit.
            matched |= MessageDigest.isEqual(
                    expectedBytes, trimmed.getBytes(StandardCharsets.UTF_8));
        }
        return matched;
    }
}
