package com.gme.pay.registry.prefunding;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * Canonical JSON writer for prefunding-config rows — the byte representation
 * that goes into the ADR-007 audit hash chain as the BEFORE / AFTER snapshot
 * of a step-5 prefunding save.
 *
 * <p>Same rationale as {@code ContactJson} / {@code KybJson} /
 * {@code SettlementJson}: the bytes that feed the hash chain must be identical
 * on every machine running the same write path, so the canonicalisation is
 * hand-rolled with a fixed key order and no dependence on live Jackson
 * configuration.
 *
 * <p>Money renders as a JSON STRING via {@link BigDecimal#toPlainString()}
 * (never scientific notation, never a float) per
 * {@code docs/MONEY_CONVENTION.md} — the service normalises every money field
 * to scale 4 before this writer runs, so the bytes are deterministic.
 *
 * <p>Shape: one object with the fixed key sequence {@code id, fundingModel,
 * openingBalanceUsd, lowBalanceThresholdUsd, alertTier70, alertTier85,
 * alertTier95, creditLimitUsd, autoSuspendOnBreach, floatTopUpBankAccountId,
 * topUpReferencePattern, collateralAmountUsd}. Bitemporal stamps are
 * intentionally excluded — they describe the row's storage history, not the
 * prefunding fact, and the audit row carries its own {@code recorded_at}.
 */
final class PrefundingJson {

    private PrefundingJson() {
        // static utility
    }

    /** Canonical UTF-8 JSON bytes for one prefunding-config row. */
    static byte[] canonical(PrefundingConfigEntity e) {
        StringBuilder sb = new StringBuilder(320);
        sb.append('{');
        sb.append("\"id\":").append(e.getId() == null ? "null" : e.getId()).append(',');
        sb.append("\"fundingModel\":").append(jsonString(e.getFundingModel())).append(',');
        sb.append("\"openingBalanceUsd\":")
                .append(money(e.getOpeningBalanceUsd())).append(',');
        sb.append("\"lowBalanceThresholdUsd\":")
                .append(money(e.getLowBalanceThresholdUsd())).append(',');
        sb.append("\"alertTier70\":").append(e.isAlertTier70()).append(',');
        sb.append("\"alertTier85\":").append(e.isAlertTier85()).append(',');
        sb.append("\"alertTier95\":").append(e.isAlertTier95()).append(',');
        sb.append("\"creditLimitUsd\":").append(money(e.getCreditLimitUsd())).append(',');
        sb.append("\"autoSuspendOnBreach\":").append(e.isAutoSuspendOnBreach()).append(',');
        sb.append("\"floatTopUpBankAccountId\":")
                .append(e.getFloatTopUpBankAccountId() == null
                        ? "null" : e.getFloatTopUpBankAccountId()).append(',');
        sb.append("\"topUpReferencePattern\":")
                .append(jsonString(e.getTopUpReferencePattern())).append(',');
        sb.append("\"collateralAmountUsd\":").append(money(e.getCollateralAmountUsd()));
        sb.append('}');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Money as a quoted plain-decimal string, or the JSON null literal. */
    private static String money(BigDecimal value) {
        return value == null ? "null" : '"' + value.toPlainString() + '"';
    }

    /**
     * Minimal JSON string escaper — byte-compatible with the one in
     * {@code AuditLogService} / {@code ContactJson} / {@code SettlementJson}
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
