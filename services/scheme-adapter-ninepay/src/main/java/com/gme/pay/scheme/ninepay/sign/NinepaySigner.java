package com.gme.pay.scheme.ninepay.sign;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;

/**
 * RSA request signer / response verifier for the 9Pay disbursement API.
 *
 * <p>9Pay's signing model (integration spec ver 3.13, section 2): every request carries a
 * {@code signature} field = {@code base64( RSA-2048 sign( canonical ) )} where
 * {@code canonical} is a <b>pipe-delimited concatenation of specific fields in a fixed,
 * per-endpoint order</b> (e.g. transfer signs
 * {@code request_id|partner_id|bank_no|account_no|account_type|account_name|amount|content}).
 * The digest algorithm is chosen by the partner and communicated to 9Pay —
 * SHA1/SHA224/SHA256/SHA384/SHA512 are supported; this adapter defaults to <b>SHA256</b>
 * ({@code gmepay.scheme.ninepay.sign-algorithm}).</p>
 *
 * <p>Key exchange is mutual: we sign with OUR private key (9Pay holds our public key);
 * 9Pay signs its responses and IPN pushes with ITS private key and we verify with 9Pay's
 * public key ({@link #verify}).</p>
 *
 * <p>Key material comes from config as PEM strings ({@code private-key-pem} — PKCS#8
 * "BEGIN PRIVATE KEY"; {@code ninepay-public-key-pem} — X.509 "BEGIN PUBLIC KEY").
 * PKCS#1 ("BEGIN RSA PRIVATE KEY") is <b>not</b> parsed here — convert with
 * {@code openssl pkcs8 -topk8 -nocrypt} first. Keys are parsed lazily on first use so the
 * service boots with the placeholder (blank) config; sign/verify then fail loudly until
 * real keys are provisioned.</p>
 */
@Component
public class NinepaySigner {

    private static final Set<String> SUPPORTED = Set.of("SHA1", "SHA224", "SHA256", "SHA384", "SHA512");

    private final String jcaAlgorithm;
    private final String privateKeyPem;
    private final String ninepayPublicKeyPem;

    private volatile PrivateKey privateKey;
    private volatile PublicKey ninepayPublicKey;

    /** Primary constructor — wired by Spring. {@code @Autowired} required (2+ ctors). */
    @Autowired
    public NinepaySigner(
            @Value("${gmepay.scheme.ninepay.sign-algorithm:SHA256}") String algorithm,
            @Value("${gmepay.scheme.ninepay.private-key-pem:}") String privateKeyPem,
            @Value("${gmepay.scheme.ninepay.ninepay-public-key-pem:}") String ninepayPublicKeyPem) {
        this.jcaAlgorithm = toJca(algorithm);
        this.privateKeyPem = privateKeyPem;
        this.ninepayPublicKeyPem = ninepayPublicKeyPem;
    }

    /** Test/alternate constructor — accepts pre-built key objects (either may be null). */
    public NinepaySigner(PrivateKey privateKey, PublicKey ninepayPublicKey, String algorithm) {
        this.jcaAlgorithm = toJca(algorithm);
        this.privateKeyPem = "";
        this.ninepayPublicKeyPem = "";
        this.privateKey = privateKey;
        this.ninepayPublicKey = ninepayPublicKey;
    }

    // ------------------------------------------------------------------ canonical

    /**
     * Builds the pipe-delimited canonical string 9Pay signs over. Field order matters and
     * is per-endpoint (see the digest doc); {@code null} renders as an empty segment.
     */
    public static String canonical(Object... fields) {
        StringJoiner joiner = new StringJoiner("|");
        for (Object field : fields) {
            joiner.add(field == null ? "" : String.valueOf(field));
        }
        return joiner.toString();
    }

    // ------------------------------------------------------------------ sign / verify

    /** Signs a canonical pipe-string with OUR private key; returns base64. */
    public String sign(String canonical) {
        try {
            Signature signature = Signature.getInstance(jcaAlgorithm);
            signature.initSign(loadPrivateKey());
            signature.update(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "9Pay request signing failed: " + e.getMessage());
        }
    }

    /**
     * Whether 9Pay's public key is present AND parseable, i.e. whether any signature can
     * be verified at all — gap <b>T5-4</b>.
     *
     * <p>Callers use this to <b>fail closed</b>: without a trust anchor a response or IPN
     * must be treated as unverifiable (ambiguous / rejected), never as trusted. Answering
     * this question separately from {@link #verify} keeps "9Pay sent a bad signature"
     * distinguishable from "we have no key to check it with" — the first is an attack or a
     * spec mismatch, the second is our own misconfiguration.
     *
     * @return {@code true} when {@link #verify} can render a real verdict
     */
    public boolean canVerify() {
        if (ninepayPublicKey != null) {
            return true;
        }
        if (ninepayPublicKeyPem == null || ninepayPublicKeyPem.isBlank()) {
            return false;
        }
        try {
            loadNinepayPublicKey();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Verifies a 9Pay response/IPN signature against the canonical pipe-string using
     * 9PAY's public key. Returns {@code false} for a bad/garbled signature; throws only
     * when the public key itself is missing/unparseable (a config problem) — callers that
     * must not surface a config fault as a business outcome should gate on
     * {@link #canVerify()} first.
     */
    public boolean verify(String canonical, String signatureB64) {
        if (signatureB64 == null || signatureB64.isBlank()) {
            return false;
        }
        try {
            Signature signature = Signature.getInstance(jcaAlgorithm);
            signature.initVerify(loadNinepayPublicKey());
            signature.update(canonical.getBytes(StandardCharsets.UTF_8));
            return signature.verify(Base64.getDecoder().decode(signatureB64));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            // Bad base64 / signature length / algorithm mismatch → not verified.
            return false;
        }
    }

    // ------------------------------------------------------------------ key loading

    private PrivateKey loadPrivateKey() {
        PrivateKey cached = privateKey;
        if (cached != null) {
            return cached;
        }
        if (privateKeyPem == null || privateKeyPem.isBlank()) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "9Pay signing key not configured (gmepay.scheme.ninepay.private-key-pem)");
        }
        if (privateKeyPem.contains("RSA PRIVATE KEY")) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "9Pay private key is PKCS#1 (BEGIN RSA PRIVATE KEY) — convert to PKCS#8: "
                            + "openssl pkcs8 -topk8 -nocrypt");
        }
        try {
            byte[] der = pemToDer(privateKeyPem);
            PrivateKey parsed = KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
            privateKey = parsed;
            return parsed;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "9Pay private key PEM unparseable: " + e.getMessage());
        }
    }

    private PublicKey loadNinepayPublicKey() {
        PublicKey cached = ninepayPublicKey;
        if (cached != null) {
            return cached;
        }
        if (ninepayPublicKeyPem == null || ninepayPublicKeyPem.isBlank()) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "9Pay public key not configured (gmepay.scheme.ninepay.ninepay-public-key-pem)");
        }
        try {
            byte[] der = pemToDer(ninepayPublicKeyPem);
            PublicKey parsed = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
            ninepayPublicKey = parsed;
            return parsed;
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "9Pay public key PEM unparseable: " + e.getMessage());
        }
    }

    private static byte[] pemToDer(String pem) {
        String body = pem.replaceAll("-----(BEGIN|END)[^-]*-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    /** Maps the configured digest name (SHA256 / SHA-256 / sha256) to the JCA algorithm id. */
    private static String toJca(String algorithm) {
        String normalized = (algorithm == null ? "SHA256" : algorithm)
                .toUpperCase(Locale.ROOT).replace("-", "").trim();
        if (!SUPPORTED.contains(normalized)) {
            throw new IllegalArgumentException(
                    "Unsupported 9Pay sign-algorithm '" + algorithm + "' — one of " + SUPPORTED);
        }
        return normalized + "withRSA";
    }
}
