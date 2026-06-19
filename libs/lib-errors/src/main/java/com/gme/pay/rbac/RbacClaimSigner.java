package com.gme.pay.rbac;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs / verifies the provenance of edge-stamped {@link RbacHeaders} claims with HMAC-SHA256.
 *
 * <p><b>Why:</b> the {@code X-Gme-*} claim headers are trusted across a network boundary. Without
 * provenance, any actor that can reach a service directly (bypassing the gateway) could forge
 * {@code X-Gme-Permissions: rbac.manage} and self-grant authority. The api-gateway is the ONLY
 * component holding the shared secret, so it is the only one that can produce a valid
 * {@code X-Gme-Sig} over a claim bundle. A forged request carries no valid signature, so
 * {@link RbacContextFilter} refuses to trust it and the caller is treated as anonymous.
 *
 * <p>The canonical signing payload binds, in a fixed order, the timestamp and every authority-bearing
 * claim the gateway stamps. The timestamp gives a freshness window (anti-replay). Both the signer
 * (gateway) and the verifier (every service) MUST build the canonical identically — hence this one
 * shared helper. Null values normalise to empty string so the gateway (which removes an absent
 * header) and a consumer (which reads it back as {@code null}) agree on the bytes.
 */
public final class RbacClaimSigner {

    private static final String HMAC = "HmacSHA256";
    private static final char SEP = '\n';

    private RbacClaimSigner() {}

    /**
     * Canonical signing payload — fixed field order, null-safe. Binds EVERY authorization-bearing
     * header the gateway stamps: the authority claims (principal/tenant/permissions/roles), the
     * encoded {@code constraints}, and the constraint-context attributes
     * (country/region/office/approvalGranted) the engine evaluates LOCATION/APPROVAL rules against.
     * Appending or altering any of them on a captured-but-valid bundle invalidates the signature.
     */
    public static String canonical(long tsMillis, String principalId, String tenantId,
                                   String permissions, String roles, String constraints,
                                   String country, String region, String office, String approvalGranted) {
        return new StringBuilder(224)
                .append(tsMillis).append(SEP)
                .append(nz(principalId)).append(SEP)
                .append(nz(tenantId)).append(SEP)
                .append(nz(permissions)).append(SEP)
                .append(nz(roles)).append(SEP)
                .append(nz(constraints)).append(SEP)
                .append(nz(country)).append(SEP)
                .append(nz(region)).append(SEP)
                .append(nz(office)).append(SEP)
                .append(nz(approvalGranted))
                .toString();
    }

    /** Lowercase-hex HMAC-SHA256 of {@code payload} under {@code secret}. */
    public static String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return toHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // HmacSHA256 is always available; a key/spec failure is a config error, not per-request.
            throw new IllegalStateException("RBAC claim signing failed", e);
        }
    }

    /** Constant-time verification of {@code candidateHex} against a fresh signature of {@code payload}. */
    public static boolean verify(String candidateHex, String payload, String secret) {
        if (candidateHex == null || candidateHex.isBlank()) return false;
        String expected = sign(payload, secret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                candidateHex.trim().getBytes(StandardCharsets.UTF_8));
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16))
              .append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
