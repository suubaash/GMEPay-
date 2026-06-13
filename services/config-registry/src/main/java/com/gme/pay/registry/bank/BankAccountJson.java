package com.gme.pay.registry.bank;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Canonical JSON writer for bank-account sets — the byte representation that
 * goes into the ADR-007 audit hash chain as the BEFORE / AFTER snapshot of a
 * step-4 bulk replace or a verification stamp.
 *
 * <p>Same rationale as {@code ContactJson} / {@code KybJson}: the bytes that
 * feed the hash chain must be identical on every machine running the same
 * write path, so the canonicalisation is hand-rolled with a fixed key order
 * and no dependence on live Jackson configuration. A reconfigured ObjectMapper
 * must never silently invalidate previously-sealed audit rows.
 *
 * <p>Shape: a JSON array, rows in id order (the order the repository returns),
 * each object carrying the fixed key sequence {@code id, currency, bankName,
 * bicSwift, ibanOrAccountNumber, accountHolderName, bankCountry,
 * intermediaryBic, verificationStatus, verificationEvidenceDocId,
 * verificationDate, primary, swiftChargeBearer, purpose}. Bitemporal stamps
 * are intentionally excluded — they describe the row's storage history, not
 * the account fact, and the audit row carries its own {@code recorded_at}.
 */
final class BankAccountJson {

    private BankAccountJson() {
        // static utility
    }

    /** Canonical UTF-8 JSON bytes for the given rows (id order assumed). */
    static byte[] canonical(List<BankAccountEntity> accounts) {
        StringBuilder sb = new StringBuilder(64 + accounts.size() * 280);
        sb.append('[');
        for (int i = 0; i < accounts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            BankAccountEntity b = accounts.get(i);
            sb.append('{');
            sb.append("\"id\":").append(b.getId() == null ? "null" : b.getId()).append(',');
            sb.append("\"currency\":").append(jsonString(b.getCurrency())).append(',');
            sb.append("\"bankName\":").append(jsonString(b.getBankName())).append(',');
            sb.append("\"bicSwift\":").append(jsonString(b.getBicSwift())).append(',');
            sb.append("\"ibanOrAccountNumber\":").append(jsonString(b.getIbanOrAccountNumber())).append(',');
            sb.append("\"accountHolderName\":").append(jsonString(b.getAccountHolderName())).append(',');
            sb.append("\"bankCountry\":").append(jsonString(b.getBankCountry())).append(',');
            sb.append("\"intermediaryBic\":").append(jsonString(b.getIntermediaryBic())).append(',');
            sb.append("\"verificationStatus\":").append(jsonString(
                    b.getVerificationStatus() == null ? null : b.getVerificationStatus().name())).append(',');
            sb.append("\"verificationEvidenceDocId\":").append(
                    b.getVerificationEvidenceDocId() == null ? "null" : b.getVerificationEvidenceDocId()).append(',');
            sb.append("\"verificationDate\":").append(jsonString(
                    b.getVerificationDate() == null ? null : b.getVerificationDate().toString())).append(',');
            sb.append("\"primary\":").append(b.isPrimaryAccount()).append(',');
            sb.append("\"swiftChargeBearer\":").append(jsonString(
                    b.getSwiftChargeBearer() == null ? null : b.getSwiftChargeBearer().name())).append(',');
            sb.append("\"purpose\":").append(jsonString(
                    b.getPurpose() == null ? null : b.getPurpose().name()));
            sb.append('}');
        }
        sb.append(']');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Minimal JSON string escaper — byte-compatible with the ones in
     * {@code ContactJson} / {@code AuditLogService} (kept local so this package
     * does not reach into another package's private helpers).
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
