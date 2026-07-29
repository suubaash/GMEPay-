package com.gme.pay.scheme.ninepay.sign;

import com.gme.pay.errors.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link NinepaySigner} unit tests: canonical pipe-string building, RSA sign/verify
 * round-trip, tamper detection, digest-algorithm variants, and PEM key loading via the
 * config constructor.
 */
class NinepaySignerTest {

    private static KeyPair rsa2048() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    // ------------------------------------------------------------------ canonical

    @Test
    @DisplayName("canonical: joins fields with pipes; null renders empty; order preserved")
    void canonical_pipeJoin() {
        assertEquals("REQ19P202607270001|GMEPAY|970418|1023020330000|0",
                NinepaySigner.canonical("REQ19P202607270001", "GMEPAY", "970418", "1023020330000", 0));
        assertEquals("a||c", NinepaySigner.canonical("a", null, "c"));
        assertEquals("50000|NGUYEN VAN A", NinepaySigner.canonical(50000L, "NGUYEN VAN A"));
    }

    // ------------------------------------------------------------------ round-trip + tamper

    @Test
    @DisplayName("sign/verify round-trip: our signature verifies against our public key")
    void signVerify_roundTrip() throws Exception {
        KeyPair pair = rsa2048();
        NinepaySigner signer = new NinepaySigner(pair.getPrivate(), pair.getPublic(), "SHA256");

        String canonical = NinepaySigner.canonical(
                "REQ1", "GMEPAY", "970418", "1023020330000", 0, "NGUYEN VAN A", 50000, "GME PAYOUT");
        String signature = signer.sign(canonical);

        assertTrue(signer.verify(canonical, signature));
    }

    @Test
    @DisplayName("verify: any tampered field in the canonical string fails verification")
    void verify_detectsTamperedCanonical() throws Exception {
        KeyPair pair = rsa2048();
        NinepaySigner signer = new NinepaySigner(pair.getPrivate(), pair.getPublic(), "SHA256");

        String signature = signer.sign(NinepaySigner.canonical("REQ1", "GMEPAY", 50000));

        // amount tampered 50000 -> 90000
        assertFalse(signer.verify(NinepaySigner.canonical("REQ1", "GMEPAY", 90000), signature));
    }

    @Test
    @DisplayName("verify: a tampered/garbled/blank signature fails without throwing")
    void verify_detectsTamperedSignature() throws Exception {
        KeyPair pair = rsa2048();
        NinepaySigner signer = new NinepaySigner(pair.getPrivate(), pair.getPublic(), "SHA256");
        String canonical = NinepaySigner.canonical("REQ1", "GMEPAY", 50000);
        String signature = signer.sign(canonical);

        byte[] bytes = Base64.getDecoder().decode(signature);
        bytes[7] ^= 0x55;
        assertFalse(signer.verify(canonical, Base64.getEncoder().encodeToString(bytes)));
        assertFalse(signer.verify(canonical, "not-base64!!!"));
        assertFalse(signer.verify(canonical, ""));
        assertFalse(signer.verify(canonical, null));
    }

    @Test
    @DisplayName("verify: a signature from a DIFFERENT key (not 9Pay's) fails")
    void verify_rejectsWrongKey() throws Exception {
        KeyPair ours = rsa2048();
        KeyPair attacker = rsa2048();
        NinepaySigner attackerSigner = new NinepaySigner(attacker.getPrivate(), attacker.getPublic(), "SHA256");
        NinepaySigner verifier = new NinepaySigner(ours.getPrivate(), ours.getPublic(), "SHA256");

        String canonical = NinepaySigner.canonical("REQ1", "GMEPAY", 50000);
        assertFalse(verifier.verify(canonical, attackerSigner.sign(canonical)));
    }

    // ------------------------------------------------------------------ algorithms

    @ParameterizedTest
    @ValueSource(strings = {"SHA1", "SHA224", "SHA256", "SHA384", "SHA512", "sha256", "SHA-512"})
    @DisplayName("all supported digest algorithms round-trip (configurable, default SHA256)")
    void algorithms_roundTrip(String algorithm) throws Exception {
        KeyPair pair = rsa2048();
        NinepaySigner signer = new NinepaySigner(pair.getPrivate(), pair.getPublic(), algorithm);
        String canonical = NinepaySigner.canonical("REQ1", "GMEPAY");
        assertTrue(signer.verify(canonical, signer.sign(canonical)));
    }

    @Test
    @DisplayName("unsupported algorithm is rejected at construction")
    void algorithms_unsupportedRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new NinepaySigner((java.security.PrivateKey) null, null, "MD5"));
    }

    // ------------------------------------------------------------------ PEM loading

    @Test
    @DisplayName("PEM constructor: PKCS#8 private + X.509 public PEMs sign/verify like key objects")
    void pem_roundTrip() throws Exception {
        KeyPair pair = rsa2048();
        String privatePem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        String publicPem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";

        NinepaySigner fromPem = new NinepaySigner("SHA256", privatePem, publicPem);
        String canonical = NinepaySigner.canonical("REQ1", "GMEPAY", 2000);
        assertTrue(fromPem.verify(canonical, fromPem.sign(canonical)));
    }

    @Test
    @DisplayName("blank key config fails loudly on use (placeholders must not sign silently)")
    void pem_blankKeysFailLoudly() {
        NinepaySigner unconfigured = new NinepaySigner("SHA256", "", "");
        assertThrows(ApiException.class, () -> unconfigured.sign("a|b"));
        assertThrows(ApiException.class, () -> unconfigured.verify("a|b", "c2ln"));
    }

    @Test
    @DisplayName("PKCS#1 private key PEM is rejected with a convert-to-PKCS#8 hint")
    void pem_pkcs1Rejected() {
        NinepaySigner signer = new NinepaySigner("SHA256",
                "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----", "");
        ApiException ex = assertThrows(ApiException.class, () -> signer.sign("a|b"));
        assertTrue(ex.getMessage().contains("PKCS#8"));
    }
}
