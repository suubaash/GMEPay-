package com.gme.pay.qr.domain.emvco;

import java.math.BigDecimal;

/**
 * Immutable result of an EMVCo QR parse operation (WBS 5.4-T01).
 *
 * <p>EMVCo tag reference:
 * <ul>
 *   <li>00 — format indicator (must be "01")</li>
 *   <li>52 — MCC</li>
 *   <li>53 — transaction currency (numeric ISO 4217, e.g. "410" = KRW)</li>
 *   <li>54 — transaction amount (optional)</li>
 *   <li>58 — country code (alpha-2)</li>
 *   <li>59 — merchant name</li>
 *   <li>60 — merchant city</li>
 *   <li>63 — CRC-16</li>
 *   <li>26-51 — MAI (Merchant Account Information) templates</li>
 * </ul>
 */
public record ParsedQRPayload(
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
