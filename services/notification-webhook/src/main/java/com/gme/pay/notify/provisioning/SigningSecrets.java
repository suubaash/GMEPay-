package com.gme.pay.notify.provisioning;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generation + one-way hashing of webhook signing secrets — Slice 8 Lane D.
 *
 * <p>SECURITY CONVENTION (SEC-09 §4, same rule as auth-identity's
 * {@code SecretHasher}): plaintext secret material is NEVER persisted. The
 * secret is a 256-bit CSPRNG value ({@code whsec_} + 43 chars of unpadded
 * base64url), so an unsalted SHA-256 digest is a cryptographically sufficient
 * at-rest representation — there is no low-entropy password for a rainbow
 * table to attack, which is the threat PBKDF2's salt + iterations exist for.
 *
 * <p>No Spring dependencies — plain JDK crypto, unit-testable without a
 * context.
 */
public final class SigningSecrets {

    /** Stripe-style recognisable prefix so leaked strings are grep-able. */
    public static final String SECRET_PREFIX = "whsec_";

    private static final int SECRET_LENGTH_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private SigningSecrets() {
        // static utility
    }

    /** @return a fresh 256-bit signing secret: {@code whsec_<43 base64url chars>}. */
    public static String newSecret() {
        byte[] material = new byte[SECRET_LENGTH_BYTES];
        RANDOM.nextBytes(material);
        return SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(material);
    }

    /** @return lowercase-hex SHA-256 digest of the secret's UTF-8 bytes (64 chars). */
    public static String sha256Hex(String secret) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("secret must not be null or empty");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Constant-time verification of a presented candidate against a stored digest. */
    public static boolean matches(String candidateSecret, String expectedHashHex) {
        if (candidateSecret == null || candidateSecret.isEmpty() || expectedHashHex == null) {
            return false;
        }
        byte[] actual = sha256Hex(candidateSecret).getBytes(StandardCharsets.UTF_8);
        byte[] expected = expectedHashHex.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
