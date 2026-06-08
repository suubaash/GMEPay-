package com.gme.pay.qr.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;

/**
 * Response body for POST /v1/qr/parse.
 *
 * <p>All fields mapped from the EMVCo tag values:
 * <ul>
 *   <li>tag 00 → formatIndicator</li>
 *   <li>tag 52 → mcc</li>
 *   <li>tag 53 → currencyCode</li>
 *   <li>tag 54 → encodedAmount (optional)</li>
 *   <li>tag 58 → countryCode</li>
 *   <li>tag 59 → merchantName</li>
 *   <li>tag 60 → merchantCity</li>
 *   <li>tag 63 → crc (verified)</li>
 *   <li>MAI slot (tags 26-51) → maiTag, merchantId, qrCodeId</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ParsedQrResponse(
        String rawPayload,
        int formatIndicator,
        String currencyCode,
        String merchantName,
        String merchantCity,
        String mcc,
        String countryCode,
        int maiTag,
        String merchantId,
        String qrCodeId,
        BigDecimal encodedAmount,
        boolean crcVerified
) {}
