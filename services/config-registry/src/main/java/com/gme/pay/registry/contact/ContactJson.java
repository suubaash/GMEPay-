package com.gme.pay.registry.contact;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Canonical JSON writer for contact sets — the byte representation that goes
 * into the ADR-007 audit hash chain as the BEFORE / AFTER snapshot of a step-2
 * bulk replace.
 *
 * <p>Same rationale as {@code AuditLogService.canonicalPartnerJson}: the bytes
 * that feed the hash chain must be identical on every machine running the same
 * write path, so the canonicalisation is hand-rolled with a fixed key order and
 * no dependence on live Jackson configuration. A reconfigured ObjectMapper (key
 * ordering, null handling) must never silently invalidate previously-sealed
 * audit rows.
 *
 * <p>Shape: a JSON array, rows in id order (the order the repository returns),
 * each object carrying the fixed key sequence {@code id, role, name, email,
 * phoneE164, authorizedSignatory, notes}. Bitemporal stamps are intentionally
 * excluded — they describe the row's storage history, not the contact fact, and
 * the audit row carries its own {@code recorded_at}.
 */
final class ContactJson {

    private ContactJson() {
        // static utility
    }

    /** Canonical UTF-8 JSON bytes for the given contact rows (id order assumed). */
    static byte[] canonical(List<ContactEntity> contacts) {
        StringBuilder sb = new StringBuilder(64 + contacts.size() * 160);
        sb.append('[');
        for (int i = 0; i < contacts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            ContactEntity c = contacts.get(i);
            sb.append('{');
            sb.append("\"id\":").append(c.getId() == null ? "null" : c.getId()).append(',');
            sb.append("\"role\":").append(jsonString(c.getRole() == null ? null : c.getRole().name())).append(',');
            sb.append("\"name\":").append(jsonString(c.getName())).append(',');
            sb.append("\"email\":").append(jsonString(c.getEmail())).append(',');
            sb.append("\"phoneE164\":").append(jsonString(c.getPhoneE164())).append(',');
            sb.append("\"authorizedSignatory\":").append(c.isAuthorizedSignatory()).append(',');
            sb.append("\"notes\":").append(jsonString(c.getNotes()));
            sb.append('}');
        }
        sb.append(']');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Minimal JSON string escaper — mirrors the one in {@code AuditLogService}
     * (kept local so the contact package does not reach into the audit package's
     * private helpers; the two must stay byte-compatible for the same input).
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
