package com.gme.pay.auth.domain;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.HexFormat;

/**
 * Salted one-way hashing for API-key / HMAC secret material (SEC-09 §4).
 *
 * <p>CONVENTION: secret material is NEVER persisted in plaintext. The
 * {@code api_keys} table (V002) stores only the PBKDF2-HMAC-SHA256 digest of
 * the secret together with its per-key random salt and the derivation
 * parameters used. The plaintext is shown to the caller exactly once at
 * issuance time; verification re-derives the digest from the presented
 * candidate and compares in constant time.</p>
 *
 * <p>The iteration count is persisted per row ({@code api_keys.hash_iterations})
 * so {@link #CURRENT_ITERATIONS} can be raised later without invalidating
 * previously issued keys.</p>
 *
 * <p>No Spring dependencies — plain JDK crypto, unit-testable without a context.</p>
 */
public final class SecretHasher {

    /** JCA algorithm name used for key derivation. Persisted in api_keys.hash_algorithm. */
    public static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    /** Iteration count for newly issued keys (OWASP 2023 guidance for PBKDF2-SHA256). */
    public static final int CURRENT_ITERATIONS = 210_000;

    private static final int SALT_LENGTH_BYTES = 16;
    private static final int KEY_LENGTH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private SecretHasher() {
    }

    /** @return a fresh cryptographically random per-key salt, lowercase hex encoded. */
    public static String newSaltHex() {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        RANDOM.nextBytes(salt);
        return HEX.formatHex(salt);
    }

    /**
     * Derives the salted hash of a secret.
     *
     * @param secret     plaintext secret (never persisted)
     * @param saltHex    hex-encoded salt from {@link #newSaltHex()}
     * @param iterations PBKDF2 iteration count to apply
     * @return lowercase hex digest (64 chars for the 256-bit key length)
     */
    public static String hashHex(String secret, String saltHex, int iterations) {
        if (secret == null || secret.isEmpty()) {
            throw new IllegalArgumentException("secret must not be null or empty");
        }
        if (saltHex == null || saltHex.isEmpty()) {
            throw new IllegalArgumentException("saltHex must not be null or empty");
        }
        PBEKeySpec spec = new PBEKeySpec(
                secret.toCharArray(), HEX.parseHex(saltHex), iterations, KEY_LENGTH_BITS);
        try {
            byte[] derived = SecretKeyFactory.getInstance(ALGORITHM)
                    .generateSecret(spec).getEncoded();
            return HEX.formatHex(derived);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException(ALGORITHM + " unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * Verifies a presented candidate secret against a stored salted hash.
     * Constant-time comparison prevents timing side-channels.
     *
     * @return true when the candidate derives to the expected hash
     */
    public static boolean matches(String candidateSecret, String saltHex, int iterations,
                                  String expectedHashHex) {
        if (candidateSecret == null || candidateSecret.isEmpty()
                || saltHex == null || expectedHashHex == null) {
            return false;
        }
        byte[] actual = HEX.parseHex(hashHex(candidateSecret, saltHex, iterations));
        byte[] expected = HEX.parseHex(expectedHashHex);
        return MessageDigest.isEqual(expected, actual);
    }
}
