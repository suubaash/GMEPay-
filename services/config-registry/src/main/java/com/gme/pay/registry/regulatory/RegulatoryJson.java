package com.gme.pay.registry.regulatory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * Canonical JSON writer for regulatory-config rows — the byte representation
 * that goes into the ADR-007 audit hash chain as the BEFORE / AFTER snapshot
 * of a step-8 regulatory save.
 *
 * <p>Same rationale as {@code PrefundingJson} / {@code CorridorJson}: the
 * bytes that feed the hash chain must be identical on every machine running
 * the same write path, so the canonicalisation is hand-rolled with a fixed
 * key order and no dependence on live Jackson configuration.
 *
 * <p>Money renders as a JSON STRING via {@link BigDecimal#toPlainString()}
 * (never scientific notation, never a float) per
 * {@code docs/MONEY_CONVENTION.md} — the service normalises both thresholds
 * to scale 2 before this writer runs, so the bytes are deterministic.
 *
 * <p>Shape: one object with the fixed key sequence {@code id, bokTxnCode,
 * bokFxReportingCategory, bokRemitterType, hometaxIssuerCertId, vatTreatment,
 * kofiuEntityId, ctrThresholdKrw, pipaJurisdictionAllowlist, legalBasisCode,
 * travelRuleProtocol, travelRuleEndpointUrl, travelRuleThresholdKrw}.
 * {@code pipaJurisdictionAllowlist} renders as the stored CSV string (the
 * service normalises ordering/whitespace before persisting). Bitemporal and
 * provenance stamps are intentionally excluded — they describe the row's
 * storage history, not the regulatory fact, and the audit row carries its own
 * {@code recorded_at} / actor.
 */
final class RegulatoryJson {

    private RegulatoryJson() {
        // static utility
    }

    /** Canonical UTF-8 JSON bytes for one regulatory-config row. */
    static byte[] canonical(PartnerRegulatoryConfigEntity e) {
        StringBuilder sb = new StringBuilder(420);
        sb.append('{');
        sb.append("\"id\":").append(e.getId() == null ? "null" : e.getId()).append(',');
        sb.append("\"bokTxnCode\":").append(jsonString(e.getBokTxnCode())).append(',');
        sb.append("\"bokFxReportingCategory\":")
                .append(jsonString(e.getBokFxReportingCategory())).append(',');
        sb.append("\"bokRemitterType\":")
                .append(jsonString(e.getBokRemitterType())).append(',');
        sb.append("\"hometaxIssuerCertId\":")
                .append(jsonString(e.getHometaxIssuerCertId())).append(',');
        sb.append("\"vatTreatment\":").append(jsonString(e.getVatTreatment())).append(',');
        sb.append("\"kofiuEntityId\":").append(jsonString(e.getKofiuEntityId())).append(',');
        sb.append("\"ctrThresholdKrw\":").append(money(e.getCtrThresholdKrw())).append(',');
        sb.append("\"pipaJurisdictionAllowlist\":")
                .append(jsonString(e.getPipaJurisdictionAllowlist())).append(',');
        sb.append("\"legalBasisCode\":").append(jsonString(e.getLegalBasisCode())).append(',');
        sb.append("\"travelRuleProtocol\":")
                .append(jsonString(e.getTravelRuleProtocol())).append(',');
        sb.append("\"travelRuleEndpointUrl\":")
                .append(jsonString(e.getTravelRuleEndpointUrl())).append(',');
        sb.append("\"travelRuleThresholdKrw\":")
                .append(money(e.getTravelRuleThresholdKrw()));
        sb.append('}');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Money as a quoted plain-decimal string, or the JSON null literal. */
    private static String money(BigDecimal value) {
        return value == null ? "null" : '"' + value.toPlainString() + '"';
    }

    /**
     * Minimal JSON string escaper — byte-compatible with the ones in
     * {@code PrefundingJson} / {@code CorridorJson} for the same input (kept
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
