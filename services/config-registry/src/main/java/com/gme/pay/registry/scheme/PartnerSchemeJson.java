package com.gme.pay.registry.scheme;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Canonical JSON writer for scheme-enablement sets — the byte representation
 * that goes into the ADR-007 audit hash chain as the BEFORE / AFTER snapshot
 * of a step-7 scheme bulk replace.
 *
 * <p>Same rationale as {@code RuleJson} / {@code PrefundingJson}: the bytes
 * that feed the hash chain must be identical on every machine running the same
 * write path, so the canonicalisation is hand-rolled with a fixed key order
 * and no dependence on live Jackson configuration.
 *
 * <p>Shape: a JSON array, rows in id order (the order the repository returns),
 * each object carrying the fixed key sequence {@code id, schemeId, direction,
 * role, zeropayMerchantId, zeropaySubMerchantId, kftcInstitutionCode,
 * partnerTypeChar, vaultSecretId, approvalMethodCpm, approvalMethodMpm,
 * enabled}. Bitemporal stamps are intentionally excluded — they describe the
 * row's storage history, not the wiring fact, and the audit row carries its
 * own {@code recorded_at}.
 */
final class PartnerSchemeJson {

    private PartnerSchemeJson() {
        // static utility
    }

    /** Canonical UTF-8 JSON bytes for the given rows (id order assumed). */
    static byte[] canonical(List<PartnerSchemeEntity> schemes) {
        StringBuilder sb = new StringBuilder(32 + schemes.size() * 240);
        sb.append('[');
        for (int i = 0; i < schemes.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            PartnerSchemeEntity s = schemes.get(i);
            sb.append('{');
            sb.append("\"id\":").append(s.getId() == null ? "null" : s.getId()).append(',');
            sb.append("\"schemeId\":").append(jsonString(s.getSchemeId())).append(',');
            sb.append("\"direction\":").append(jsonString(s.getDirection())).append(',');
            sb.append("\"role\":").append(jsonString(s.getRole())).append(',');
            sb.append("\"zeropayMerchantId\":")
                    .append(jsonString(s.getZeropayMerchantId())).append(',');
            sb.append("\"zeropaySubMerchantId\":")
                    .append(jsonString(s.getZeropaySubMerchantId())).append(',');
            sb.append("\"kftcInstitutionCode\":")
                    .append(jsonString(s.getKftcInstitutionCode())).append(',');
            sb.append("\"partnerTypeChar\":")
                    .append(jsonString(s.getPartnerTypeChar())).append(',');
            sb.append("\"vaultSecretId\":").append(jsonString(s.getVaultSecretId())).append(',');
            sb.append("\"approvalMethodCpm\":")
                    .append(jsonString(s.getApprovalMethodCpm())).append(',');
            sb.append("\"approvalMethodMpm\":")
                    .append(jsonString(s.getApprovalMethodMpm())).append(',');
            sb.append("\"enabled\":")
                    .append(s.getEnabled() == null ? "null" : s.getEnabled().toString());
            sb.append('}');
        }
        sb.append(']');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Minimal JSON string escaper — byte-compatible with the ones in
     * {@code RuleJson} / {@code PrefundingJson} for the same input (kept local
     * so this package does not reach into other packages' private helpers).
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
