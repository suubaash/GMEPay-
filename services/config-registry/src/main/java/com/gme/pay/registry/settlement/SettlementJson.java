package com.gme.pay.registry.settlement;

import java.nio.charset.StandardCharsets;

/**
 * Canonical JSON writer for settlement-config rows — the byte representation
 * that goes into the ADR-007 audit hash chain as the BEFORE / AFTER snapshot
 * of a step-4 settlement save.
 *
 * <p>Same rationale as {@code ContactJson} / {@code KybJson}: the bytes that
 * feed the hash chain must be identical on every machine running the same
 * write path, so the canonicalisation is hand-rolled with a fixed key order
 * and no dependence on live Jackson configuration.
 *
 * <p>Shape: one object with the fixed key sequence {@code id, cycleTPlusN,
 * cutoffTime, cutoffTimezone, settlementMethod}. Bitemporal stamps are
 * intentionally excluded — they describe the row's storage history, not the
 * settlement fact, and the audit row carries its own {@code recorded_at}.
 */
final class SettlementJson {

    private SettlementJson() {
        // static utility
    }

    /** Canonical UTF-8 JSON bytes for one settlement-config row. */
    static byte[] canonical(SettlementConfigEntity e) {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        sb.append("\"id\":").append(e.getId() == null ? "null" : e.getId()).append(',');
        sb.append("\"cycleTPlusN\":")
                .append(e.getCycleTPlusN() == null ? "null" : e.getCycleTPlusN()).append(',');
        sb.append("\"cutoffTime\":")
                .append(jsonString(e.getCutoffTime() == null ? null : e.getCutoffTime().toString()))
                .append(',');
        sb.append("\"cutoffTimezone\":").append(jsonString(e.getCutoffTimezone())).append(',');
        sb.append("\"settlementMethod\":").append(jsonString(e.getSettlementMethod()));
        sb.append('}');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Minimal JSON string escaper — byte-compatible with the one in
     * {@code AuditLogService} / {@code ContactJson} for the same input (kept
     * local so this package does not reach into other packages' private
     * helpers).
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
