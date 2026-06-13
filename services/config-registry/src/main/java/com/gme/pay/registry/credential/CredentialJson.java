package com.gme.pay.registry.credential;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Canonical JSON writers for the Slice 8 Lane B aggregates — the byte
 * representations that feed the ADR-007 audit hash chain as BEFORE / AFTER
 * snapshots of IP-allowlist replaces, mTLS-cert uploads and credential
 * ledger transitions.
 *
 * <p>Same rationale as {@code RuleJson} / {@code ContactJson} /
 * {@code BankAccountJson}: hash-chain bytes must be identical on every
 * machine running the same write path, so the canonicalisation is hand-rolled
 * with a fixed key order and no dependence on live Jackson configuration.
 *
 * <p>SEC-09 §4: none of these writers ever see secret material — the
 * credential snapshot carries the key id + prefix + last-4 residue only, and
 * the cert snapshot carries the fingerprint, not the PEM body.
 */
final class CredentialJson {

    private CredentialJson() {
        // static utility
    }

    /** Canonical UTF-8 JSON bytes for an allowlist row set (env+cidr order assumed). */
    static byte[] allowlist(List<PartnerIpAllowlistEntity> rows) {
        StringBuilder sb = new StringBuilder(32 + rows.size() * 96);
        sb.append('[');
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            PartnerIpAllowlistEntity r = rows.get(i);
            sb.append('{');
            sb.append("\"id\":").append(r.getId() == null ? "null" : r.getId()).append(',');
            sb.append("\"cidr\":").append(jsonString(r.getCidr())).append(',');
            sb.append("\"label\":").append(jsonString(r.getLabel())).append(',');
            sb.append("\"environment\":").append(jsonString(r.getEnvironment()));
            sb.append('}');
        }
        sb.append(']');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Canonical UTF-8 JSON bytes for a cert row set (fingerprint, NEVER the PEM). */
    static byte[] certs(List<PartnerMtlsCertEntity> rows) {
        StringBuilder sb = new StringBuilder(32 + rows.size() * 192);
        sb.append('[');
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            PartnerMtlsCertEntity r = rows.get(i);
            sb.append('{');
            sb.append("\"id\":").append(r.getId() == null ? "null" : r.getId()).append(',');
            sb.append("\"environment\":").append(jsonString(r.getEnvironment())).append(',');
            sb.append("\"fingerprintSha256\":")
                    .append(jsonString(r.getFingerprintSha256())).append(',');
            sb.append("\"subjectDn\":").append(jsonString(r.getSubjectDn())).append(',');
            sb.append("\"issuerDn\":").append(jsonString(r.getIssuerDn())).append(',');
            sb.append("\"notBefore\":").append(instant(r.getNotBefore())).append(',');
            sb.append("\"notAfter\":").append(instant(r.getNotAfter())).append(',');
            sb.append("\"status\":").append(jsonString(r.getStatus()));
            sb.append('}');
        }
        sb.append(']');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Canonical UTF-8 JSON bytes for a credential ledger row set (no secrets, ever). */
    static byte[] credentials(List<PartnerCredentialEntity> rows) {
        StringBuilder sb = new StringBuilder(32 + rows.size() * 192);
        sb.append('[');
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            PartnerCredentialEntity r = rows.get(i);
            sb.append('{');
            sb.append("\"id\":").append(r.getId() == null ? "null" : r.getId()).append(',');
            sb.append("\"environment\":").append(jsonString(r.getEnvironment())).append(',');
            sb.append("\"credentialKind\":")
                    .append(jsonString(r.getCredentialKind())).append(',');
            sb.append("\"authIdentityKeyId\":")
                    .append(jsonString(r.getAuthIdentityKeyId())).append(',');
            sb.append("\"prefix\":").append(jsonString(r.getPrefix())).append(',');
            sb.append("\"last4\":").append(jsonString(r.getLast4())).append(',');
            sb.append("\"issuedAt\":").append(instant(r.getIssuedAt())).append(',');
            sb.append("\"expiresAt\":").append(instant(r.getExpiresAt())).append(',');
            sb.append("\"rotatedAt\":").append(instant(r.getRotatedAt())).append(',');
            sb.append("\"revokedAt\":").append(instant(r.getRevokedAt())).append(',');
            sb.append("\"status\":").append(jsonString(r.getStatus()));
            sb.append('}');
        }
        sb.append(']');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** ISO-8601 instant as a quoted string, or the JSON null literal. */
    private static String instant(Instant value) {
        return value == null ? "null" : '"' + value.toString() + '"';
    }

    /**
     * Minimal JSON string escaper — byte-compatible with the ones in
     * {@code RuleJson} / {@code ContactJson} for the same input (kept local so
     * this package does not reach into other packages' private helpers).
     */
    private static String jsonString(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }
}
