package com.gme.pay.registry.rule;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Canonical JSON writer for rule sets — the byte representation that goes into
 * the ADR-007 audit hash chain as the BEFORE / AFTER snapshot of a step-6 rule
 * bulk replace.
 *
 * <p>Same rationale as {@code ContactJson} / {@code BankAccountJson} /
 * {@code PrefundingJson}: the bytes that feed the hash chain must be identical
 * on every machine running the same write path, so the canonicalisation is
 * hand-rolled with a fixed key order and no dependence on live Jackson
 * configuration.
 *
 * <p>Shape: a JSON array, rows in id order (the order the repository returns),
 * each object carrying the fixed key sequence {@code id, schemeId, direction,
 * mA, mB, serviceChargeUsd}. Margins and money render as JSON STRINGS via
 * {@link BigDecimal#toPlainString()} (never scientific notation, never a
 * float) per {@code docs/MONEY_CONVENTION.md} — the service normalises every
 * decimal field to scale 4 before this writer runs, so the bytes are
 * deterministic. Bitemporal stamps are intentionally excluded — they describe
 * the row's storage history, not the pricing fact, and the audit row carries
 * its own {@code recorded_at}.
 */
final class RuleJson {

    private RuleJson() {
        // static utility
    }

    /** Canonical UTF-8 JSON bytes for the given rows (id order assumed). */
    static byte[] canonical(List<RuleEntity> rules) {
        StringBuilder sb = new StringBuilder(32 + rules.size() * 160);
        sb.append('[');
        for (int i = 0; i < rules.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            RuleEntity r = rules.get(i);
            sb.append('{');
            sb.append("\"id\":").append(r.getId() == null ? "null" : r.getId()).append(',');
            sb.append("\"schemeId\":").append(jsonString(r.getSchemeId())).append(',');
            sb.append("\"direction\":").append(jsonString(r.getDirection())).append(',');
            sb.append("\"mA\":").append(decimal(r.getMA())).append(',');
            sb.append("\"mB\":").append(decimal(r.getMB())).append(',');
            sb.append("\"serviceChargeUsd\":").append(decimal(r.getServiceChargeUsd()));
            sb.append('}');
        }
        sb.append(']');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Decimal as a quoted plain-decimal string, or the JSON null literal. */
    private static String decimal(BigDecimal value) {
        return value == null ? "null" : '"' + value.toPlainString() + '"';
    }

    /**
     * Minimal JSON string escaper — byte-compatible with the ones in
     * {@code ContactJson} / {@code BankAccountJson} / {@code PrefundingJson}
     * for the same input (kept local so this package does not reach into other
     * packages' private helpers).
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
