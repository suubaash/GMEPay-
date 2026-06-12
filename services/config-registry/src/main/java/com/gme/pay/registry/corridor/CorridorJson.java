package com.gme.pay.registry.corridor;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Canonical JSON writer for corridor sets — the byte representation that goes
 * into the ADR-007 audit hash chain as the BEFORE / AFTER snapshot of a step-7
 * corridor bulk replace.
 *
 * <p>Same rationale as {@code RuleJson} / {@code ContactJson} /
 * {@code BankAccountJson}: the bytes that feed the hash chain must be
 * identical on every machine running the same write path, so the
 * canonicalisation is hand-rolled with a fixed key order and no dependence on
 * live Jackson configuration.
 *
 * <p>Shape: a JSON array, rows in id order (the order the repository returns),
 * each object carrying the fixed key sequence {@code id, srcCountry, srcCcy,
 * dstCountry, dstCcy, goLiveDate, isActive}. {@code goLiveDate} renders as an
 * ISO-8601 {@code yyyy-MM-dd} JSON string ({@link java.time.LocalDate#toString}
 * is exactly that) or the JSON null literal; {@code isActive} as a JSON
 * boolean literal. Bitemporal stamps are intentionally excluded — they
 * describe the row's storage history, not the corridor fact, and the audit row
 * carries its own {@code recorded_at}.
 */
final class CorridorJson {

    private CorridorJson() {
        // static utility
    }

    /** Canonical UTF-8 JSON bytes for the given rows (id order assumed). */
    static byte[] canonical(List<PartnerCorridorEntity> corridors) {
        StringBuilder sb = new StringBuilder(32 + corridors.size() * 140);
        sb.append('[');
        for (int i = 0; i < corridors.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            PartnerCorridorEntity c = corridors.get(i);
            sb.append('{');
            sb.append("\"id\":").append(c.getId() == null ? "null" : c.getId()).append(',');
            sb.append("\"srcCountry\":").append(jsonString(c.getSrcCountry())).append(',');
            sb.append("\"srcCcy\":").append(jsonString(c.getSrcCcy())).append(',');
            sb.append("\"dstCountry\":").append(jsonString(c.getDstCountry())).append(',');
            sb.append("\"dstCcy\":").append(jsonString(c.getDstCcy())).append(',');
            sb.append("\"goLiveDate\":").append(
                    c.getGoLiveDate() == null ? "null" : '"' + c.getGoLiveDate().toString() + '"');
            sb.append(',');
            sb.append("\"isActive\":").append(
                    c.getIsActive() == null ? "null" : c.getIsActive().toString());
            sb.append('}');
        }
        sb.append(']');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
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
