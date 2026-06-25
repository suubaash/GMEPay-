package com.gme.pay.registry.commercial;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Canonical JSON writers for the four Slice-6 commercial aggregates — the
 * byte representations that go into the ADR-007 audit hash chain as the
 * BEFORE / AFTER snapshots of a step-6 commercial save.
 *
 * <p>Same rationale as {@code ContactJson} / {@code KybJson} /
 * {@code SettlementJson} / {@code PrefundingJson} / {@code BankAccountJson}:
 * the bytes that feed the hash chain must be identical on every machine
 * running the same write path, so the canonicalisation is hand-rolled with a
 * fixed key order and no dependence on live Jackson configuration. The four
 * writers share one class because the aggregates live (and are reviewed) as
 * one wizard step; each still seals under its own {@code aggregateType}.
 *
 * <p>Money / bps render as JSON STRINGS via
 * {@link BigDecimal#toPlainString()} (never scientific notation, never a
 * float) per {@code docs/MONEY_CONVENTION.md} — the services normalise scale
 * before these writers run, so the bytes are deterministic. Bitemporal stamps
 * are intentionally excluded — they describe row storage history, not the
 * commercial fact, and the audit row carries its own {@code recorded_at}.
 */
final class CommercialJson {

    private CommercialJson() {
        // static utility
    }

    /**
     * Canonical UTF-8 JSON bytes for a fee-schedule row set (id order
     * assumed). Shape: array of {@code {id, schemeId, direction, fixedFeeUsd,
     * bpsFee, tiers}} where {@code tiers} embeds the stored
     * {@link FeeTierTableJson} canonical array verbatim (or the null
     * literal).
     */
    static byte[] canonicalFeeSchedules(List<FeeScheduleEntity> rows) {
        StringBuilder sb = new StringBuilder(64 + rows.size() * 200);
        sb.append('[');
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            FeeScheduleEntity f = rows.get(i);
            sb.append('{');
            sb.append("\"id\":").append(f.getId() == null ? "null" : f.getId()).append(',');
            sb.append("\"schemeId\":").append(jsonString(f.getSchemeId())).append(',');
            sb.append("\"direction\":").append(jsonString(f.getDirection())).append(',');
            sb.append("\"fixedFeeUsd\":").append(money(f.getFixedFeeUsd())).append(',');
            sb.append("\"bpsFee\":").append(money(f.getBpsFee())).append(',');
            // The stored column IS the canonical form (FeeTierTableJson),
            // embed it raw — re-serialising could only introduce drift.
            sb.append("\"tiers\":")
                    .append(f.getTierTableJson() == null ? "null" : f.getTierTableJson());
            sb.append('}');
        }
        sb.append(']');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Canonical UTF-8 JSON bytes for one FX-config row. */
    static byte[] canonicalFxConfig(FxConfigEntity e) {
        StringBuilder sb = new StringBuilder(160);
        sb.append('{');
        sb.append("\"id\":").append(e.getId() == null ? "null" : e.getId()).append(',');
        sb.append("\"marginBps\":").append(money(e.getMarginBps())).append(',');
        sb.append("\"referenceRateSource\":")
                .append(jsonString(e.getReferenceRateSource())).append(',');
        sb.append("\"quoteHoldSeconds\":")
                .append(e.getQuoteHoldSeconds() == null ? "null" : e.getQuoteHoldSeconds()).append(',');
        sb.append("\"disclosedPartnerMargin\":")
                .append(Boolean.TRUE.equals(e.getDisclosedPartnerMargin()));
        sb.append('}');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Canonical UTF-8 JSON bytes for one limits row. */
    static byte[] canonicalLimits(LimitsEntity e) {
        StringBuilder sb = new StringBuilder(240);
        sb.append('{');
        sb.append("\"id\":").append(e.getId() == null ? "null" : e.getId()).append(',');
        sb.append("\"perTxnMinUsd\":").append(money(e.getPerTxnMinUsd())).append(',');
        sb.append("\"perTxnMaxUsd\":").append(money(e.getPerTxnMaxUsd())).append(',');
        sb.append("\"dailyCapUsd\":").append(money(e.getDailyCapUsd())).append(',');
        sb.append("\"monthlyCapUsd\":").append(money(e.getMonthlyCapUsd())).append(',');
        sb.append("\"annualCapUsd\":").append(money(e.getAnnualCapUsd())).append(',');
        sb.append("\"licenseType\":").append(jsonString(e.getLicenseType()));
        sb.append('}');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Canonical UTF-8 JSON bytes for one contract row (dates as ISO-8601 strings). */
    static byte[] canonicalContract(ContractEntity e) {
        StringBuilder sb = new StringBuilder(240);
        sb.append('{');
        sb.append("\"id\":").append(e.getId() == null ? "null" : e.getId()).append(',');
        sb.append("\"effectiveFrom\":").append(jsonString(
                e.getEffectiveFrom() == null ? null : e.getEffectiveFrom().toString())).append(',');
        sb.append("\"effectiveTo\":").append(jsonString(
                e.getEffectiveTo() == null ? null : e.getEffectiveTo().toString())).append(',');
        sb.append("\"autoRenewal\":").append(e.isAutoRenewal()).append(',');
        sb.append("\"noticePeriodDays\":")
                .append(e.getNoticePeriodDays() == null ? "null" : e.getNoticePeriodDays()).append(',');
        sb.append("\"refundChargebackPolicy\":")
                .append(jsonString(e.getRefundChargebackPolicy())).append(',');
        sb.append("\"terminationReason\":").append(jsonString(e.getTerminationReason()));
        sb.append('}');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Money / bps as a quoted plain-decimal string, or the JSON null literal. */
    private static String money(BigDecimal value) {
        return value == null ? "null" : '"' + value.toPlainString() + '"';
    }

    /**
     * Minimal JSON string escaper — byte-compatible with the one in
     * {@code AuditLogService} / {@code ContactJson} / {@code PrefundingJson}
     * for the same input (kept local so this package does not reach into
     * other packages' private helpers).
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
