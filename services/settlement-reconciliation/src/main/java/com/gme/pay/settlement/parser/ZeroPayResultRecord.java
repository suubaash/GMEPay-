package com.gme.pay.settlement.parser;

import java.math.BigDecimal;

/**
 * A single data record parsed from a ZeroPay inbound result file.
 *
 * <p>Covers all four file types:
 * <ul>
 *   <li>ZP0062 – morning settlement result (net settlement per merchant)</li>
 *   <li>ZP0064 – afternoon settlement result (net settlement per merchant)</li>
 *   <li>ZP0012 – payment registration result (transaction-level approval)</li>
 *   <li>ZP0022 – refund registration result (transaction-level refund approval)</li>
 * </ul>
 *
 * <p>Fields present depend on the {@link FileType}:
 * <ul>
 *   <li>ZP0062/ZP0064 detail lines: {@code merchantId} + {@code amount}</li>
 *   <li>ZP0012/ZP0022 detail lines: {@code txnRef} + {@code schemeRef} + {@code amount} + {@code resultCode}</li>
 * </ul>
 *
 * <p>Money amounts are {@link BigDecimal} strings to satisfy the project money convention (never
 * cast to {@code double}).
 */
public record ZeroPayResultRecord(
        FileType fileType,
        RecordType recordType,

        /** Present for settlement files (ZP0062/ZP0064). Null for registration results. */
        String merchantId,

        /**
         * GME-internal transaction reference. Present for ZP0012/ZP0022. Null for settlement files.
         */
        String txnRef,

        /**
         * ZeroPay's own transaction reference. Present for ZP0012/ZP0022. Null for settlement files.
         */
        String schemeRef,

        /**
         * Settlement/transaction amount in KRW (integer). Non-null for DATA records.
         * Null for HEADER and TRAILER.
         */
        BigDecimal amount,

        /**
         * ZeroPay result code. "0000" = success. Non-null for ZP0012/ZP0022 DATA records.
         * Null for settlement file records.
         */
        String resultCode,

        /**
         * Raw line text retained for debugging / audit trail.
         */
        String rawLine
) {

    public enum FileType {
        ZP0062, ZP0064, ZP0012, ZP0022
    }

    public enum RecordType {
        HEADER, DATA, TRAILER
    }

    /** True when this is a ZP0012/ZP0022 approval record with resultCode "0000". */
    public boolean isApproved() {
        return "0000".equals(resultCode);
    }

    /** True when this is a settlement detail record (ZP0062 or ZP0064). */
    public boolean isSettlementData() {
        return recordType == RecordType.DATA
                && (fileType == FileType.ZP0062 || fileType == FileType.ZP0064);
    }
}
