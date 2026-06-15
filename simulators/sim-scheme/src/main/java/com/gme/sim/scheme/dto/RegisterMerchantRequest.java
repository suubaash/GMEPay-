package com.gme.sim.scheme.dto;

import com.gme.sim.scheme.model.MerchantType;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for POST /v1/scheme/merchants.
 * <p>
 * The four original required fields are unchanged.
 * All ZeroPay-specific fields are optional (nullable).
 */
public record RegisterMerchantRequest(
        // --- required (unchanged) ---
        @NotBlank String merchantId,
        @NotBlank String name,
        @NotBlank String city,
        @NotBlank String mcc,

        // --- optional ZeroPay / KFTC fields ---
        /** 사업자등록번호, e.g. "123-45-67890". */
        String businessRegNo,
        /** ZeroPay sub-merchant id assigned by KFTC. */
        String subMerchantId,
        /** KFTC institution code for this merchant. */
        String kftcInstitutionCode,
        /** Bank code where ZeroPay credits next-business-day settlements. */
        String settlementBankCode,
        /** Account number for settlement credits. */
        String settlementAccountNo,
        /** Fee tier: SMALL_BIZ (0 % fee) or GENERAL (0.80 % fee). Null → GENERAL assumed. */
        MerchantType merchantType
) {}
