package com.gme.pay.scheme.sendmn.crypto;

import com.gme.pay.errors.ApiException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Round-trip tests for the best-effort {@link RsaAesEnvelopeCodec} (RSA-4096-OAEP-wrapped
 * AES-256-GCM). The exact SendMN wire spec is pending (open issue O2) — these tests pin
 * OUR layout so the codec is internally consistent until the real sample arrives.
 */
class RsaAesEnvelopeCodecTest {

    private static KeyPair keyPair;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(4096);
        keyPair = gen.generateKeyPair();
    }

    private RsaAesEnvelopeCodec codec() {
        // Round-trip with a single keypair: encrypt toward the public key, decrypt with
        // the matching private key (in production these belong to SendMN / us respectively).
        return new RsaAesEnvelopeCodec(keyPair.getPublic(), keyPair.getPrivate());
    }

    @Test
    @DisplayName("round-trip: hybrid encrypt then decrypt returns the original JSON")
    void roundTrip() {
        RsaAesEnvelopeCodec codec = codec();
        String json = "{\"TX_TOKEN_NO\":\"SMN202510241041\",\"LOCAL_PAYMENT_AMOUNT\":\"10000.00\","
                + "\"SETTLEMENT_AMOUNT\":\"2.9647\",\"MERCHANT_NAME\":\"Улаанбаатар дэлгүүр\"}";

        String enveloped = codec.encrypt(json);

        assertNotEquals(json, enveloped);
        assertEquals(json, codec.decrypt(enveloped));
    }

    @Test
    @DisplayName("fresh AES session key per call: same plaintext → different envelopes, both decrypt")
    void freshSessionKeyPerCall() {
        RsaAesEnvelopeCodec codec = codec();
        String json = "{\"TX_TOKEN_NO\":\"SMN-1\"}";

        String first = codec.encrypt(json);
        String second = codec.encrypt(json);

        assertNotEquals(first, second, "hybrid mode must generate a fresh AES key/IV per message");
        assertEquals(json, codec.decrypt(first));
        assertEquals(json, codec.decrypt(second));
    }

    @Test
    @DisplayName("tampered ciphertext fails GCM authentication → ApiException")
    void tamperDetected() {
        RsaAesEnvelopeCodec codec = codec();
        String enveloped = codec.encrypt("{\"TX_TOKEN_NO\":\"SMN-2\"}");
        byte[] raw = java.util.Base64.getDecoder().decode(enveloped);
        raw[raw.length - 1] ^= 0x01; // flip a ciphertext/tag bit
        String tampered = java.util.Base64.getEncoder().encodeToString(raw);

        assertThrows(ApiException.class, () -> codec.decrypt(tampered));
    }

    @Test
    @DisplayName("missing keys fail loudly on use (placeholder config), not at construction")
    void missingKeysFailOnUse() {
        RsaAesEnvelopeCodec noKeys = new RsaAesEnvelopeCodec(null, null);

        assertThrows(ApiException.class, () -> noKeys.encrypt("{}"));
        assertThrows(ApiException.class, () -> noKeys.decrypt("AAAA"));
    }

    @Test
    @DisplayName("mode is 'rsa'")
    void mode() {
        assertEquals("rsa", codec().mode());
    }
}
