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
     * Constant-time verification of a webhook signature header.
     *
     * @param rawBody         raw UTF-8 request body
     * @param secret          plaintext signing secret
     * @param signatureHeader the value of X-GME-Webhook-Signature (must start with {@code sha256=})
     * @return {@code true} iff the header is a valid HMAC-SHA256 of {@code rawBody} under {@code secret}
     */
    public boolean verifySignature(String rawBody, String secret, String signatureHeader) {
        if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        String expected = sign(rawBody.getBytes(StandardCharsets.UTF_8), secret);
        // Constant-time comparison to prevent timing attacks
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                signatureHeader.getBytes(StandardCharsets.UTF_8)
        );
    }
}
