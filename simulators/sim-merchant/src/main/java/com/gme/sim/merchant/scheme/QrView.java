package com.gme.sim.merchant.scheme;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A built QR rendered for the terminal: the payload plus the discrete scheme fields it
 * carries, so the UI can show the QR↔wire linkage without re-parsing.
 *
 * @param qrPayload       full EMVCo string (render as QR)
 * @param mode            "MPM_STATIC" | "MPM_DYNAMIC"
 * @param qrDivision      QR구분 ("1" static, "2" dynamic)
 * @param registrarId     등록기관ID
 * @param merchantId      가맹점ID
 * @param qrSerial        거래일련번호 (null/empty for static)
 * @param checkChar       체크문자 (null/empty for static)
 * @param currencyAlpha   ISO-4217 alpha (KRW)
 * @param currencyNumeric ISO-4217 numeric (410)
 * @param amount          embedded amount, or null for static
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QrView(
        String qrPayload, String mode, String qrDivision, String registrarId,
        String merchantId, String qrSerial, String checkChar,
        String currencyAlpha, String currencyNumeric, Long amount) {}
