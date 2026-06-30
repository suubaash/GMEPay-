package com.gme.pay.registry.kyb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.contracts.UboView;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Canonical JSON for the KYB aggregate — feeds BOTH the {@code ubo_set_jsonb}
 * column and the ADR-007 audit hash chain (BEFORE/AFTER snapshots of every
 * step-3 save and screening run).
 *
 * <p>Same rationale as {@code ContactJson} / {@code AuditLogService
 * .canonicalPartnerJson}: bytes that participate in the hash chain must be
 * identical on every machine running the same write path, so the writer is
 * hand-rolled with a fixed key order and zero dependence on live Jackson
 * configuration. Storing the SAME canonical text in the column means the
 * tamper check is byte-exact: a psql {@code UPDATE} of any KYB field makes
 * the row disagree with its sealed audit AFTER snapshot.
 *
 * <p>Parsing (column text → {@link UboView} list for the read path) uses a
 * shared {@link ObjectMapper} — parse direction does not feed the hash, so
 * Jackson is safe there.
 *
 * <h2>Shapes</h2>
 *
 * <ul>
 *   <li>UBO array: {@code [{"name":..,"ownershipPct":..,"isPep":..,"country":..}, ...]}
 *       — ownershipPct as a plain-string decimal (MONEY_CONVENTION discipline:
 *       never floating-point in hashed bytes).</li>
 *   <li>Row snapshot: fixed key sequence {@code id, riskRating, riskRationale,
 *       nextReviewDate, licenseType, licenseNumber, licenseAuthority,
 *       licenseExpiry, uboSet, cbddqDocId, screeningStatus,
 *       screeningProviderRef, screenedAt}. Bitemporal stamps are excluded —
 *       they describe storage history, not the KYB fact (same exclusion as
 *       ContactJson).</li>
 * </ul>
 */
final class KybJson {

    /** Parse-only mapper (never feeds the hash chain). */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KybJson() {
        // static utility
    }

    /**
     * Canonical UTF-8 JSON text for a UBO list. {@code null} input → {@code null}
     * (column NULL = "not captured yet"); empty list → {@code "[]"} (operator
     * explicitly declared no UBOs above threshold).
     */
    static String canonicalUbos(List<UboView> ubos) {
        if (ubos == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(16 + ubos.size() * 96);
        sb.append('[');
        for (int i = 0; i < ubos.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            UboView u = ubos.get(i);
            sb.append('{');
            sb.append("\"name\":").append(jsonString(u.name())).append(',');
            sb.append("\"ownershipPct\":").append(u.ownershipPct() == null
                    ? "null" : jsonString(u.ownershipPct().toPlainString())).append(',');
            sb.append("\"isPep\":").append(Boolean.TRUE.equals(u.isPep())).append(',');
            sb.append("\"country\":").append(jsonString(u.country()));
            sb.append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    /** Materialise the stored UBO text back into views; NULL/blank column → null. */
    static List<UboView> parseUbos(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode array = MAPPER.readTree(json);
            List<UboView> out = new ArrayList<>(array.size());
            for (JsonNode node : array) {
                out.add(new UboView(
                        textOrNull(node, "name"),
                        node.hasNonNull("ownershipPct")
                                ? new BigDecimal(node.get("ownershipPct").asText())
                                : null,
                        node.hasNonNull("isPep") && node.get("isPep").asBoolean(),
                        textOrNull(node, "country")));
            }
            return out;
        } catch (Exception e) {
            // The column is only ever written by canonicalUbos, so a parse
            // failure means out-of-band tampering or corruption — surface it
            // rather than silently returning a partial set.
            throw new IllegalStateException("unparseable ubo_set_jsonb payload", e);
        }
    }

    /** Canonical UTF-8 snapshot bytes of one KYB row for the ADR-007 hash chain. */
    static byte[] canonical(KybEntity k) {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        sb.append("\"id\":").append(k.getId() == null ? "null" : k.getId()).append(',');
        sb.append("\"riskRating\":").append(jsonString(k.getRiskRating())).append(',');
        sb.append("\"riskRationale\":").append(jsonString(k.getRiskRationale())).append(',');
        sb.append("\"nextReviewDate\":").append(jsonString(
                k.getNextReviewDate() == null ? null : k.getNextReviewDate().toString())).append(',');
        sb.append("\"licenseType\":").append(jsonString(k.getLicenseType())).append(',');
        sb.append("\"licenseNumber\":").append(jsonString(k.getLicenseNumber())).append(',');
        sb.append("\"licenseAuthority\":").append(jsonString(k.getLicenseAuthority())).append(',');
        sb.append("\"licenseExpiry\":").append(jsonString(
                k.getLicenseExpiry() == null ? null : k.getLicenseExpiry().toString())).append(',');
        // The UBO set is already canonical text — embed verbatim (it was
        // produced by canonicalUbos on the write path).
        sb.append("\"uboSet\":").append(k.getUboSetJson() == null ? "null" : k.getUboSetJson()).append(',');
        sb.append("\"cbddqDocId\":").append(k.getCbddqDocId() == null ? "null" : k.getCbddqDocId()).append(',');
        sb.append("\"screeningStatus\":").append(jsonString(k.getScreeningStatus())).append(',');
        sb.append("\"screeningProviderRef\":").append(jsonString(k.getScreeningProviderRef())).append(',');
        sb.append("\"screenedAt\":").append(jsonString(
                k.getScreenedAt() == null ? null : k.getScreenedAt().toString())).append(',');
        // Wave-3 (V036) verify verdict — appended so the audit AFTER snapshot
        // seals it too; NULL on rows that only ever screened.
        sb.append("\"verificationDecision\":")
                .append(jsonString(k.getVerificationDecision())).append(',');
        sb.append("\"verificationDecisionReason\":")
                .append(jsonString(k.getVerificationDecisionReason()));
        sb.append('}');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String textOrNull(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
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
