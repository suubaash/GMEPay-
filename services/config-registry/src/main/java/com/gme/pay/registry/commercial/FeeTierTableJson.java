package com.gme.pay.registry.commercial;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.contracts.FeeTier;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Canonical writer + parser for {@code partner_fee_schedule.tier_table_json}
 * (V018) — the volume-band table of one fee row.
 *
 * <p>WRITE side is hand-rolled with a fixed key order
 * ({@code fromVolumeUsd, bpsOverride}) and money as plain-decimal STRINGS —
 * the stored TEXT participates in the ADR-007 audit snapshot bytes (via
 * {@link CommercialJson}), so it must be byte-identical on every machine
 * running the same write path, independent of live Jackson configuration.
 * Same rationale as {@code KybJson.canonicalUbos} for V011's
 * {@code ubo_set_jsonb}.
 *
 * <p>READ side parses with a private, default-configured {@link ObjectMapper}
 * — the column is only ever written by {@link #canonical}, so a parse failure
 * means out-of-band tampering or corruption and surfaces as
 * {@link IllegalStateException} rather than silently returning a partial
 * table (the {@code KybJson.parseUbos} discipline).
 */
final class FeeTierTableJson {

    /** Private parser; never used on the write path (see class comment). */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FeeTierTableJson() {
        // static utility
    }

    /**
     * Canonical JSON array for the given tiers (caller pre-validates order
     * and scale-4 normalises the values), or {@code null} for none — NULL in
     * the column means "flat pricing".
     */
    static String canonical(List<FeeTier> tiers) {
        if (tiers == null || tiers.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(16 + tiers.size() * 64);
        sb.append('[');
        for (int i = 0; i < tiers.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            FeeTier t = tiers.get(i);
            sb.append('{');
            sb.append("\"fromVolumeUsd\":\"")
                    .append(t.fromVolumeUsd().toPlainString()).append("\",");
            sb.append("\"bpsOverride\":\"")
                    .append(t.bpsOverride().toPlainString()).append('"');
            sb.append('}');
        }
        sb.append(']');
        return sb.toString();
    }

    /** Parse a stored tier table back to the wire shape; {@code null} stays {@code null}. */
    static List<FeeTier> parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode array = MAPPER.readTree(json);
            List<FeeTier> out = new ArrayList<>(array.size());
            for (JsonNode node : array) {
                out.add(new FeeTier(
                        new BigDecimal(node.get("fromVolumeUsd").asText()),
                        new BigDecimal(node.get("bpsOverride").asText())));
            }
            return out;
        } catch (Exception e) {
            // Only canonical() ever writes the column — see class comment.
            throw new IllegalStateException("unparseable tier_table_json payload", e);
        }
    }
}
