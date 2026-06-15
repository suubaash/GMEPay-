package com.gme.sim.scheme.model;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;

import java.math.BigDecimal;

/**
 * In-memory merchant record.
 * <p>
 * Fields added for ZeroPay realism are OPTIONAL (nullable). Existing 4-arg
 * constructor is preserved so legacy callers (tests, seeds) continue to compile.
 * <p>
 * feeRate is serialized as a JSON string (not a JSON number) per the project
 * BigDecimal-as-string convention, so consumers receive the exact scale
 * (e.g. "0.0000" not 0.0).
 */
public record MerchantRecord(
        // --- existing fields (unchanged) ---
        String merchantId,
        String name,
        String city,
        String mcc,

        // --- ZeroPay / KFTC additive fields (all nullable) ---
        /** 사업자등록번호, e.g. "123-45-67890". */
        String businessRegNo,
        /** ZeroPay sub-merchant id assigned by KFTC. */
        String subMerchantId,
        /** KFTC institution code for this merchant. */
        String kftcInstitutionCode,
        /** Bank code where ZeroPay credits settlements. */
        String settlementBankCode,
        /** Account number where ZeroPay credits settlements. */
        String settlementAccountNo,
        /** Fee tier. Null treated as GENERAL. */
        MerchantType merchantType,
        /** Effective fee rate derived from merchantType; serialized as JSON string. */
        @JsonSerialize(using = ToStringSerializer.class)
        BigDecimal feeRate
) {

    /**
     * Compact canonical constructor — derives feeRate from merchantType.
     * All new ZeroPay fields may be null.
     */
    public MerchantRecord {
        // feeRate is always derived from merchantType; caller must not set it independently
        feeRate = merchantType == null ? null : merchantType.feeRate;
    }

    /**
     * Legacy 4-arg constructor — keeps existing callers/tests green.
     * All ZeroPay fields default to null.
     */
    public MerchantRecord(String merchantId, String name, String city, String mcc) {
        this(merchantId, name, city, mcc,
                null, null, null, null, null, null, null);
    }
}
