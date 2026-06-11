package com.gme.pay.auth.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link SecretHasher} — the salted-hash convention for API-key
 * secret material (SEC-09 §4). Pure JDK crypto, no Spring context.
 */
class SecretHasherTest {

    private static final String SECRET = "partner-secret-material-123";

    @Test
    void newSaltHex_is32LowercaseHexChars_andRandom() {
        String salt1 = SecretHasher.newSaltHex();
        String salt2 = SecretHasher.newSaltHex();

        assertThat(salt1).matches("[0-9a-f]{32}");
        assertThat(salt2).matches("[0-9a-f]{32}");
        assertThat(salt1).as("salts must be random per key").isNotEqualTo(salt2);
    }

    @Test
    void hashHex_isDeterministicForSameSaltAndSecret() {
        String salt = SecretHasher.newSaltHex();

        String first = SecretHasher.hashHex(SECRET, salt, SecretHasher.CURRENT_ITERATIONS);
        String second = SecretHasher.hashHex(SECRET, salt, SecretHasher.CURRENT_ITERATIONS);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void hashHex_is64LowercaseHexChars_andNeverContainsPlaintext() {
        String salt = SecretHasher.newSaltHex();

        String hash = SecretHasher.hashHex(SECRET, salt, SecretHasher.CURRENT_ITERATIONS);

        assertThat(hash).matches("[0-9a-f]{64}");
        assertThat(hash).as("digest must not embed the plaintext").doesNotContain(SECRET);
        assertThat(hash).isNotEqualTo(SECRET);
    }

    @Test
    void hashHex_differsAcrossSalts() {
        String hashA = SecretHasher.hashHex(SECRET, SecretHasher.newSaltHex(),
                SecretHasher.CURRENT_ITERATIONS);
        String hashB = SecretHasher.hashHex(SECRET, SecretHasher.newSaltHex(),
                SecretHasher.CURRENT_ITERATIONS);

        assertThat(hashA).as("same secret, different salt -> different hash").isNotEqualTo(hashB);
    }

    @Test
    void matches_acceptsCorrectSecret_rejectsWrongOne() {
        String salt = SecretHasher.newSaltHex();
        String hash = SecretHasher.hashHex(SECRET, salt, SecretHasher.CURRENT_ITERATIONS);

        assertThat(SecretHasher.matches(SECRET, salt, SecretHasher.CURRENT_ITERATIONS, hash))
                .isTrue();
        assertThat(SecretHasher.matches("wrong-secret", salt, SecretHasher.CURRENT_ITERATIONS, hash))
                .isFalse();
    }

    @Test
    void matches_isNullSafe() {
        String salt = SecretHasher.newSaltHex();
        String hash = SecretHasher.hashHex(SECRET, salt, SecretHasher.CURRENT_ITERATIONS);

        assertThat(SecretHasher.matches(null, salt, SecretHasher.CURRENT_ITERATIONS, hash)).isFalse();
        assertThat(SecretHasher.matches(SECRET, salt, SecretHasher.CURRENT_ITERATIONS, null)).isFalse();
        assertThat(SecretHasher.matches("", salt, SecretHasher.CURRENT_ITERATIONS, hash)).isFalse();
    }

    @Test
    void hashHex_rejectsBlankInputs() {
        assertThatThrownBy(() -> SecretHasher.hashHex("", "ab", 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretHasher.hashHex(SECRET, "", 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
