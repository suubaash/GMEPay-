package com.gme.sim.ninepay.sign;

import com.gme.sim.ninepay.config.NinepaySimConfig;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/**
 * The sim's side of 9Pay's RSA pipe-string signing model — the mirror image of the
 * adapter's {@code NinepaySigner}:
 *
 * <ul>
 *   <li><b>verifyPartner</b> — checks the {@code signature} on inbound partner requests
 *       against the configured partner public key ({@code sim.ninepay.partner-public-key-pem}
 *       or runtime {@code POST /sim/partner-key}). While no partner key is configured the
 *       sim is lenient and accepts requests unverified (dev mode).</li>
 *   <li><b>sign</b> — signs response/IPN canonical strings with the SIM'S OWN RSA-2048 key
 *       (generated at startup when {@code private-key-pem} is blank; the public half is
 *       exposed at {@code GET /sim/public-key} so the adapter can trust it).</li>
 * </ul>
 *
 * <p>Canonical strings are pipe-delimited field concatenations in fixed per-endpoint order
 * (null → empty segment), identical to {@code NinepaySigner.canonical}. The digest is
 * configurable SHA1/SHA224/SHA256/SHA384/SHA512, default SHA256.</p>
 */
@Component
public class SimSigner {

    private static final Set<String> SUPPORTED = Set.of("SHA1", "SHA224", "SHA256", "SHA384", "SHA512");

    private final String jcaAlgorithm;
    private final PrivateKey simPrivateKey;
    private final PublicKey simPublicKey;

    /** Partner request-verification key; volatile — settable at runtime via /sim/partner-key. */
    private volatile PublicKey partnerPublicKey;

    public SimSigner(NinepaySimConfig config) {
        this.jcaAlgorithm = toJca(config.getSignAlgorithm());
        try {
            if (config.getPrivateKeyPem() == null || config.getPrivateKeyPem().isBlank()) {
                KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
                generator.initialize(2048);
                KeyPair pair = generator.generateKeyPair();
                this.simPrivateKey = pair.getPrivate();
                this.simPublicKey = pair.getPublic();
            } else {
                this.simPrivateKey = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(pemToDer(config.getPrivateKeyPem())));
                this.simPublicKey = derivePublic(this.simPrivateKey);
            }
            if (config.getPartnerPublicKeyPem() != null && !config.getPartnerPublicKeyPem().isBlank()) {
                this.partnerPublicKey = parsePublicPem(config.getPartnerPublicKeyPem());
            }
        } catch (Exception e) {
            throw new IllegalStateException("sim-ninepay key setup failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ canonical

    /** Pipe-delimited canonical string, per-endpoint field order; null → empty segment. */
    public static String canonical(Object... fields) {
        StringJoiner joiner = new StringJoiner("|");
        for (Object field : fields) {
            joiner.add(field == null ? "" : String.valueOf(field));
        }
        return joiner.toString();
    }

    // ------------------------------------------------------------------ sign / verify

    /** Signs a canonical string with the SIM'S key (responses + IPNs); returns base64. */
    public String sign(String canonical) {
        try {
            Signature signature = Signature.getInstance(jcaAlgorithm);
            signature.initSign(simPrivateKey);
            signature.update(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException("sim-ninepay signing failed: " + e.getMessage(), e);
        }
    }

    /**
     * Verifies an inbound partner request signature. Returns {@code true} while no partner
     * public key is configured (lenient dev mode); otherwise a strict RSA verify — bad
     * base64 / mismatch → {@code false}.
     */
    public boolean verifyPartner(String canonical, String signatureB64) {
        PublicKey key = partnerPublicKey;
        if (key == null) {
            return true; // lenient until a partner key is provisioned
        }
        if (signatureB64 == null || signatureB64.isBlank()) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance(jcaAlgorithm);
            signature.initVerify(key);
            signature.update(canonical.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signatureB64));
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ key exposure / runtime config

    /** Whether partner request signatures are actually being verified. */
    public boolean isVerifyingRequests() {
        return partnerPublicKey != null;
    }

    /** Sets/replaces the partner public key at runtime (POST /sim/partner-key). */
    public void setPartnerPublicKeyPem(String pem) {
        try {
            this.partnerPublicKey = (pem == null || pem.isBlank()) ? null : parsePublicPem(pem);
        } catch (Exception e) {
            throw new IllegalArgumentException("partner public key PEM unparseable: " + e.getMessage(), e);
        }
    }

    /** The sim's public key as PEM X.509 — feed to the adapter's ninepay-public-key-pem. */
    public String publicKeyPem() {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
                .encodeToString(simPublicKey.getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
    }

    /** JCA digest id in use (e.g. {@code SHA256withRSA}) — surfaced on /sim/public-key. */
    public String algorithm() {
        return jcaAlgorithm;
    }

    // ------------------------------------------------------------------ helpers

    private static PublicKey derivePublic(PrivateKey privateKey) throws Exception {
        if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
            throw new IllegalStateException(
                    "configured private key is not an RSA CRT key — cannot derive the public half");
        }
        return KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent()));
    }

    private static PublicKey parsePublicPem(String pem) throws Exception {
        return KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(pemToDer(pem)));
    }

    private static byte[] pemToDer(String pem) {
        String body = pem.replaceAll("-----(BEGIN|END)[^-]*-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static String toJca(String algorithm) {
        String normalized = (algorithm == null ? "SHA256" : algorithm)
                .toUpperCase(Locale.ROOT).replace("-", "").trim();
        if (!SUPPORTED.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Unsupported sim.ninepay.sign-algorithm '" + algorithm + "' — one of " + SUPPORTED);
        }
        return normalized + "withRSA";
    }
}
