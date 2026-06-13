package com.gme.pay.registry.document;

import java.nio.charset.StandardCharsets;

/**
 * Canonical JSON writer for one document row — the byte representation that
 * goes into the ADR-007 audit hash chain as the BEFORE / AFTER snapshot of an
 * upload.
 *
 * <p>Same rationale as {@code ContactJson} / {@code AuditLogService.canonicalPartnerJson}:
 * the bytes feeding the hash chain must be identical on every machine running
 * the same write path, so the canonicalisation is hand-rolled with a fixed key
 * order and no dependence on live Jackson configuration.
 *
 * <p>Shape: one JSON object with the fixed key sequence {@code id, docType,
 * filename, contentType, vaultUri, version, sha256, expiryDate}. Bitemporal
 * stamps and the verification stamp are intentionally excluded — they describe
 * storage/workflow history, not the stored-document fact, and the audit row
 * carries its own {@code recorded_at}.
 */
final class DocumentJson {

    private DocumentJson() {
        // static utility
    }

    /** Canonical UTF-8 JSON bytes for one document row. */
    static byte[] canonical(DocumentEntity d) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"id\":").append(d.getId() == null ? "null" : d.getId()).append(',');
        sb.append("\"docType\":")
                .append(jsonString(d.getDocType() == null ? null : d.getDocType().name())).append(',');
        sb.append("\"filename\":").append(jsonString(d.getFilename())).append(',');
        sb.append("\"contentType\":").append(jsonString(d.getContentType())).append(',');
        sb.append("\"vaultUri\":").append(jsonString(d.getVaultUri())).append(',');
        sb.append("\"version\":").append(d.getVersion()).append(',');
        sb.append("\"sha256\":").append(jsonString(d.getSha256())).append(',');
        sb.append("\"expiryDate\":")
                .append(jsonString(d.getExpiryDate() == null ? null : d.getExpiryDate().toString()));
        sb.append('}');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Minimal JSON string escaper — byte-compatible with the ones in
     * {@code AuditLogService} / {@code ContactJson} (kept local so packages do
     * not reach into each other's private helpers).
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
