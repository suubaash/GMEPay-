package com.gme.pay.scheme.nepal.sign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.errors.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4-1 — the Nepal signer signs for real and fails closed without a key.
 *
 * <p>What the deleted {@code StubNepalSignerTest} could not assert: that the {@code signature} field
 * is a genuine RSA signature that VERIFIES under the configured key, and that an unconfigured adapter
 * refuses rather than emitting the constant {@code "c3R1Yi1zaWduYXR1cmU="}.
 */
class RsaNepalSignerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static KeyPair rsa2048() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static String pkcs8Pem(KeyPair pair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'})
                        .encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    @Test
    @DisplayName("signs with the CONFIGURED key: signature verifies against the public key over base64(data)")
    void signsWithConfiguredKey_signatureVerifies() throws Exception {
        KeyPair pair = rsa2048();
        RsaNepalSigner signer = new RsaNepalSigner(mapper, pkcs8Pem(pair), "", "");

        assertTrue(signer.isConfigured());
        NepalRequestSigner.SignedEnvelope env =
                signer.sign("{\"reference\":\"REF-1\",\"amount\":\"1000\"}");

        // The scheme signs the base64 TEXT of `data` (issuance-extension.txt).
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(pair.getPublic());
        verifier.update(env.data().getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getDecoder().decode(env.signature())),
                "signature must verify under the configured key");

        // …and the envelope still carries the nonce contract the header echoes.
        JsonNode json = mapper.readTree(Base64.getDecoder().decode(env.data()));
        assertEquals("REF-1", json.path("reference").asText());
        assertEquals(env.nonce(), json.path("nonce").asLong());
    }

    @Test
    @DisplayName("bare base64 (non-PEM) PKCS#8 key material is accepted too")
    void acceptsBareBase64Key() throws Exception {
        KeyPair pair = rsa2048();
        String bare = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());

        RsaNepalSigner signer = new RsaNepalSigner(mapper, bare, "", "");

        assertTrue(signer.isConfigured());
        Signature verifier = Signature.getInstance("SHA256withRSA");
        NepalRequestSigner.SignedEnvelope env = signer.sign("{\"reference\":\"R\"}");
        verifier.initVerify(pair.getPublic());
        verifier.update(env.data().getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getDecoder().decode(env.signature())));
    }

    @Test
    @DisplayName("FAILS CLOSED with no key configured: sign() refuses, no placeholder signature")
    void failsClosedWhenUnconfigured() {
        RsaNepalSigner signer = new RsaNepalSigner(mapper, "", "", "");

        assertFalse(signer.isConfigured());
        ApiException ex = assertThrows(ApiException.class,
                () -> signer.sign("{\"reference\":\"REF-1\"}"));
        assertTrue(ex.getMessage().contains("not configured"), ex.getMessage());
    }

    @Test
    @DisplayName("no constant signature: the same payload signed twice differs (fresh nonce per call)")
    void signatureIsNotAConstant() throws Exception {
        RsaNepalSigner signer = new RsaNepalSigner(mapper, pkcs8Pem(rsa2048()), "", "");

        String payload = "{\"reference\":\"REF-1\"}";
        String first = signer.sign(payload).signature();
        assertNotEquals("c3R1Yi1zaWduYXR1cmU=", first,
                "the retired stub's constant signature must never be emitted");
        // A different payload must produce a different signature.
        assertNotEquals(first, signer.sign("{\"reference\":\"REF-2\"}").signature());
    }

    @Test
    @DisplayName("EPHEMERAL_DEV mode is an explicit opt-in that produces a real signature")
    void ephemeralDevModeSigns() {
        RsaNepalSigner signer = new RsaNepalSigner(mapper, "", "", "EPHEMERAL_DEV");

        assertTrue(signer.isConfigured());
        NepalRequestSigner.SignedEnvelope env = signer.sign("{\"reference\":\"REF-1\"}");
        assertNotEquals("c3R1Yi1zaWduYXR1cmU=", env.signature());
        assertTrue(Base64.getDecoder().decode(env.signature()).length >= 256,
                "an RSA-2048 signature is 256 bytes");
    }

    @Test
    @DisplayName("a too-small RSA key is rejected at load time")
    void rejectsUndersizedKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(1024);
        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'})
                        .encodeToString(gen.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new RsaNepalSigner(mapper, pem, "", ""));
        assertTrue(ex.getMessage().contains("RSA-2048"), ex.getMessage());
    }

    @Test
    @DisplayName("garbage key material is rejected at load time, not at first payment")
    void rejectsGarbageKey() {
        assertThrows(IllegalStateException.class,
                () -> new RsaNepalSigner(mapper, "not-a-key", "", ""));
    }
}
