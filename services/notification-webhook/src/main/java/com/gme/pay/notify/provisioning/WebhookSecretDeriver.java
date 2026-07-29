package com.gme.pay.notify.provisioning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;

/**
 * Derives the <b>per-endpoint</b> webhook signing secret — gap <b>T5-4</b>.
 *
 * <h2>What was wrong</h2>
 *
 * <p>{@code DefaultWebhookTargetResolver} signed EVERY partner's webhooks with the one
 * global {@code gmepay.webhook.signing-secret}. Two consequences: (1) a partner who
 * holds that value can forge events for every other partner, and (2) the
 * {@code whsec_} secret handed out at activation was pure CSPRNG randomness that the
 * dispatcher never used — so a partner verifying the signature the way the API doc
 * tells them to could never get a match.
 *
 * <h2>The model now</h2>
 *
 * <p>The secret is <b>derived</b>, not stored: HKDF-SHA256 (RFC 5869) over a single
 * high-value root key with the endpoint's own identity as the {@code info} string:
 *
 * <pre>
 *   PRK    = HMAC-SHA256(salt = "GMEPAY-WEBHOOK-SECRET-V1", ikm = rootKey)
 *   OKM    = HMAC-SHA256(PRK, info || 0x01)                    (L = 32 bytes)
 *   info   = "webhook-endpoint|partner=&lt;id&gt;|env=&lt;ENV&gt;|gen=&lt;n&gt;"
 *   secret = "whsec_" + base64url-nopad(OKM)
 * </pre>
 *
 * <p>Properties this buys:
 * <ul>
 *   <li><b>Per-endpoint isolation.</b> Partner 7's secret is a one-way function of the
 *       root key; holding it reveals nothing about partner 8's, so it cannot forge
 *       events to anyone else. (With the global secret, every partner held the key to
 *       every other partner's webhooks.)</li>
 *   <li><b>Nothing new at rest.</b> The project-wide rule "plaintext secret material is
 *       NEVER persisted" (SEC-09 §4) stays literally true: the row still carries only
 *       {@link SigningSecrets#sha256Hex(String) SHA-256} of the secret, and the
 *       dispatcher re-derives the plaintext when it needs to sign. No new encrypted
 *       column, no KEK, no IV/AEAD to get wrong.</li>
 *   <li><b>Rotation is a counter.</b> Bumping {@code generation} yields a completely
 *       independent secret with no re-keying of anything else
 *       ({@code WebhookEndpointProvisioningService.rotateSecret}).</li>
 *   <li><b>Verifiable.</b> Because the row stores the hash, the resolver can check that
 *       what it derived is the very secret the partner was handed before it signs with
 *       it — a mis-set root key produces NO delivery rather than a wrong signature.</li>
 * </ul>
 *
 * <p>The root key reuses the existing {@code gmepay.webhook.signing-secret} /
 * {@code GMEPAY_WEBHOOK_SIGNING_SECRET} slot (already templated in the Helm values), so
 * no new deployment variable is required — but its MEANING changed: it is now a
 * derivation root that is <b>never disclosed to any partner</b>, where before it was
 * the shared HMAC key that every partner needed a copy of.
 *
 * <p><b>Fail closed:</b> when the root key is blank nothing can be derived.
 * {@link #isConfigured()} is false, registration falls back to a CSPRNG secret (so
 * partner activation still completes and the T1-1 registration contract is unchanged),
 * and the resolver refuses to deliver — it never falls back to a shared key.
 */
@Component
public class WebhookSecretDeriver {

    private static final Logger log = LoggerFactory.getLogger(WebhookSecretDeriver.class);

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /** HKDF-Extract salt — domain separation from every other key derived off this root. */
    private static final byte[] SALT = "GMEPAY-WEBHOOK-SECRET-V1".getBytes(StandardCharsets.UTF_8);

    /** HKDF output length: 256 bits, matching {@link SigningSecrets#newSecret()}. */
    private static final int OKM_LENGTH_BYTES = 32;

    /** Generation of an endpoint whose secret has never been rotated. */
    public static final int INITIAL_GENERATION = 1;

    /** HKDF pseudo-random key; {@code null} when no root key is configured. */
    private final byte[] prk;

    /** Spring constructor — the root key comes from configuration. */
    @Autowired
    public WebhookSecretDeriver(
            @Value("${gmepay.webhook.signing-secret:}") String rootKey) {
        this.prk = (rootKey == null || rootKey.isBlank()) ? null : extract(rootKey);
        if (this.prk == null) {
            log.warn("webhook signing-secret ROOT KEY is not configured "
                    + "(gmepay.webhook.signing-secret / GMEPAY_WEBHOOK_SIGNING_SECRET). "
                    + "Per-endpoint secrets cannot be derived, so NO webhook will be "
                    + "delivered (fail closed) — rows stay PENDING until it is set.");
        }
    }

    /** Test/embedding factory: build a deriver over an explicit root key ("" = disabled). */
    public static WebhookSecretDeriver withRootKey(String rootKey) {
        return new WebhookSecretDeriver(rootKey);
    }

    /** @return true when a root key is present and per-endpoint secrets can be derived. */
    public boolean isConfigured() {
        return prk != null;
    }

    /**
     * Derives the signing secret for one endpoint generation.
     *
     * @param partnerId   owning partner
     * @param environment {@code SANDBOX} | {@code LIVE} (part of the identity: the same
     *                    partner's sandbox secret must not sign live events)
     * @param generation  1-based rotation counter ({@code webhook_endpoint.secret_generation})
     * @return {@code whsec_<43 base64url chars>}
     * @throws IllegalStateException when no root key is configured (never returns a
     *         guessable or shared fallback)
     */
    public String derive(Long partnerId, String environment, int generation) {
        if (prk == null) {
            throw new IllegalStateException(
                    "cannot derive a webhook signing secret: gmepay.webhook.signing-secret is not set");
        }
        if (partnerId == null) {
            throw new IllegalArgumentException("partnerId is required to derive a webhook secret");
        }
        if (generation < INITIAL_GENERATION) {
            throw new IllegalArgumentException("generation must be >= " + INITIAL_GENERATION
                    + ", was: " + generation);
        }
        String info = "webhook-endpoint|partner=" + partnerId
                + "|env=" + (environment == null ? "" : environment)
                + "|gen=" + generation;
        byte[] okm = expand(info);
        try {
            return SigningSecrets.SECRET_PREFIX
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(okm);
        } finally {
            Arrays.fill(okm, (byte) 0);
        }
    }

    // ------------------------------------------------------------------ RFC 5869

    private static byte[] extract(String rootKey) {
        byte[] ikm = rootKey.getBytes(StandardCharsets.UTF_8);
        try {
            return hmac(SALT, ikm);
        } finally {
            Arrays.fill(ikm, (byte) 0);
        }
    }

    /** HKDF-Expand for a single output block (L = 32 = HashLen, so T(1) is the whole OKM). */
    private byte[] expand(String info) {
        byte[] infoBytes = info.getBytes(StandardCharsets.UTF_8);
        byte[] input = Arrays.copyOf(infoBytes, infoBytes.length + 1);
        input[infoBytes.length] = 0x01;
        byte[] okm = hmac(prk, input);
        if (okm.length != OKM_LENGTH_BYTES) {
            throw new IllegalStateException("unexpected HKDF output length " + okm.length);
        }
        return okm;
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
