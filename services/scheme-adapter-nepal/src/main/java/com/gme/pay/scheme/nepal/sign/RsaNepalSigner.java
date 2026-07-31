package com.gme.pay.scheme.nepal.sign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

/**
 * The real {@link NepalRequestSigner}: RSA-2048 / PKCS#1 v1.5 / SHA-256 over the base64 envelope, per
 * {@code API-DOCS/issuance-extension.txt}. Replaces {@code StubNepalSigner}, which returned the
 * constant {@code "c3R1Yi1zaWduYXR1cmU="} for every request (gap T4-1).
 *
 * <h2>What it signs</h2>
 * <ol>
 *   <li>Inject {@code nonce} = current UNIX seconds into the request JSON (the caller echoes it in the
 *       {@code X-KhaltiNonce} header; the scheme accepts {@code serverTs-100 .. serverTs+200}).</li>
 *   <li>{@code data = base64(json)}.</li>
 *   <li>{@code signature = base64( RSA_sign_SHA256( ASCII(data) ) )} — the signature covers the
 *       base64 TEXT, which is what the scheme verifies, not the raw JSON bytes.</li>
 * </ol>
 *
 * <h2>Key material — configuration only, and fail closed</h2>
 * The private key is supplied by configuration and <b>never</b> committed:
 * <ul>
 *   <li>{@code gmepay.scheme.nepal.signing.private-key} — a PKCS#8 key, either PEM
 *       ({@code -----BEGIN PRIVATE KEY-----}) or bare base64 DER. Intended for an env var / secret
 *       mount ({@code GMEPAY_SCHEME_NEPAL_SIGNING_PRIVATE_KEY}).</li>
 *   <li>{@code gmepay.scheme.nepal.signing.private-key-path} — a filesystem path to the same, for a
 *       mounted secret file.</li>
 *   <li>{@code gmepay.scheme.nepal.signing.mode=EPHEMERAL_DEV} — <b>explicit opt-in</b> for local/sim
 *       runs with no scheme credentials: generates a throwaway RSA-2048 keypair at startup and logs a
 *       loud warning. The sim soft-logs signatures, so the full signing path still executes for real
 *       instead of being short-circuited by a fake constant. It is NOT a default and NOT usable
 *       against the real scheme (the scheme holds no matching public key).</li>
 * </ul>
 *
 * <p>With none of these set, the bean still starts (so {@code /decode} and health work) but every
 * {@link #sign} call fails with a clear {@link ErrorCode#SCHEME_UNAVAILABLE} — a signed Nepal request
 * is never emitted with a placeholder signature.
 */
@Component
public class RsaNepalSigner implements NepalRequestSigner {

    private static final Logger log = LoggerFactory.getLogger(RsaNepalSigner.class);

    /** JCA algorithm for the scheme's documented RSA / PKCS#1 v1.5 / SHA-256 signature. */
    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    /** Minimum acceptable modulus size. The scheme specifies RSA-2048. */
    private static final int MIN_KEY_BITS = 2048;

    /** Opt-in mode that mints a throwaway keypair for local/sim runs. */
    static final String MODE_EPHEMERAL_DEV = "EPHEMERAL_DEV";

    private final ObjectMapper mapper;
    /** Null when no key material is configured — {@link #sign} then fails closed. */
    private final PrivateKey privateKey;
    /** Where the key came from, for the startup log line. Never contains key material. */
    private final String keySource;

    @Autowired
    public RsaNepalSigner(
            ObjectMapper mapper,
            @Value("${gmepay.scheme.nepal.signing.private-key:}") String privateKeyMaterial,
            @Value("${gmepay.scheme.nepal.signing.private-key-path:}") String privateKeyPath,
            @Value("${gmepay.scheme.nepal.signing.mode:}") String mode) {
        this.mapper = mapper;
        Loaded loaded = load(privateKeyMaterial, privateKeyPath, mode);
        this.privateKey = loaded.key();
        this.keySource = loaded.source();
        if (privateKey == null) {
            log.warn("Nepal request signing is NOT configured — signed /pay and /status calls will be"
                    + " REFUSED. Set gmepay.scheme.nepal.signing.private-key(-path), or"
                    + " gmepay.scheme.nepal.signing.mode={} for local/sim runs.", MODE_EPHEMERAL_DEV);
        } else {
            log.info("Nepal request signer active ({}, {}-bit RSA)", keySource, keyBits(privateKey));
        }
    }

    /** Test constructor — an already-loaded key (or null to exercise the fail-closed path). */
    RsaNepalSigner(ObjectMapper mapper, PrivateKey privateKey) {
        this.mapper = mapper;
        this.privateKey = privateKey;
        this.keySource = "explicit";
    }

    @Override
    public SignedEnvelope sign(String jsonPayload) {
        if (privateKey == null) {
            // Fail CLOSED. A constant/placeholder signature would be accepted by the sim and rejected
            // by the real scheme, i.e. it would look like it worked right up to production.
            throw new ApiException(ErrorCode.SCHEME_UNAVAILABLE,
                    "Nepal request signing key is not configured — refusing to send an unsigned"
                            + " request. Set gmepay.scheme.nepal.signing.private-key(-path).");
        }
        long nonce = Instant.now().getEpochSecond();
        String dataB64;
        try {
            ObjectNode node = (ObjectNode) mapper.readTree(jsonPayload);
            node.put("nonce", nonce);
            dataB64 = Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(node));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "failed to build signed Nepal envelope: " + e.getMessage());
        }
        try {
            Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
            signer.initSign(privateKey);
            // The scheme signs the base64 TEXT of `data`, not the raw JSON.
            signer.update(dataB64.getBytes(StandardCharsets.US_ASCII));
            String signature = Base64.getEncoder().encodeToString(signer.sign());
            return new SignedEnvelope(dataB64, signature, nonce);
        } catch (GeneralSecurityException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "failed to RSA-sign the Nepal envelope: " + e.getMessage());
        }
    }

    /** True when key material is available, i.e. this adapter can emit signed requests. */
    public boolean isConfigured() {
        return privateKey != null;
    }

    // ---------------------------------------------------------------------------------------
    // key loading
    // ---------------------------------------------------------------------------------------

    private static Loaded load(String material, String path, String mode) {
        if (material != null && !material.isBlank()) {
            return new Loaded(parsePkcs8(material, "gmepay.scheme.nepal.signing.private-key"),
                    "gmepay.scheme.nepal.signing.private-key");
        }
        if (path != null && !path.isBlank()) {
            String contents;
            try {
                contents = Files.readString(Path.of(path.trim()), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "gmepay.scheme.nepal.signing.private-key-path is set but unreadable: " + e.getMessage(), e);
            }
            return new Loaded(parsePkcs8(contents, "gmepay.scheme.nepal.signing.private-key-path"),
                    "gmepay.scheme.nepal.signing.private-key-path=" + path.trim());
        }
        if (mode != null && MODE_EPHEMERAL_DEV.equalsIgnoreCase(mode.trim())) {
            log.warn("Nepal signing mode={} — generating a THROWAWAY RSA-{} keypair. Valid only against"
                            + " sim-nepal-qr (which soft-logs signatures); the real scheme holds no matching"
                            + " public key. NEVER set this outside local/sim.",
                    MODE_EPHEMERAL_DEV, MIN_KEY_BITS);
            return new Loaded(generateEphemeral(), MODE_EPHEMERAL_DEV);
        }
        return new Loaded(null, "unconfigured");
    }

    /** Parse PEM or bare-base64 PKCS#8 into an RSA private key, validating algorithm + size. */
    private static PrivateKey parsePkcs8(String raw, String origin) {
        String base64 = raw
                .replaceAll("-----BEGIN (RSA )?PRIVATE KEY-----", "")
                .replaceAll("-----END (RSA )?PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(origin
                    + " is not valid base64/PEM PKCS#8 key material", e);
        }
        PrivateKey key;
        try {
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(origin
                    + " is not a PKCS#8-encoded RSA private key (PKCS#1 'BEGIN RSA PRIVATE KEY' must be"
                    + " converted: openssl pkcs8 -topk8 -nocrypt): " + e.getMessage(), e);
        }
        int bits = keyBits(key);
        if (bits < MIN_KEY_BITS) {
            throw new IllegalStateException(origin + " is a " + bits + "-bit RSA key; the Nepal scheme"
                    + " requires at least RSA-" + MIN_KEY_BITS);
        }
        return key;
    }

    private static PrivateKey generateEphemeral() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(MIN_KEY_BITS);
            KeyPair pair = gen.generateKeyPair();
            return pair.getPrivate();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to generate an ephemeral Nepal signing key", e);
        }
    }

    private static int keyBits(PrivateKey key) {
        return key instanceof RSAPrivateKey rsa ? rsa.getModulus().bitLength() : 0;
    }

    /** Loaded key + a provenance label safe to log (never the key itself). */
    private record Loaded(PrivateKey key, String source) {
    }
}
