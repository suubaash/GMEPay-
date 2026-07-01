package com.gme.pay.payment.domain;

import java.util.Locale;

/**
 * Detects whether a raw wallet-scanned QR payload is a Nepal (Fonepay / NepalPay) QR.
 *
 * <p>The wallet's {@code POST /v1/pay} entry point carries only a {@code partner} field
 * (GMEREMIT / SENDMN) — it does NOT tell the hub which scheme a scanned QR belongs to. A
 * Nepal Fonepay QR arrives with {@code partner=GMEREMIT} (the wallet's issuing partner) and
 * was therefore mis-routed down the ZeroPay domestic path, failing with MERCHANT_NOT_FOUND /
 * HUB_ERROR. This detector inspects the QR content itself so the controller can route Nepal
 * QRs to the Nepal adapter regardless of the {@code partner} value.
 *
 * <h2>Markers (ANY match &rarr; Nepal)</h2>
 * <ul>
 *   <li>contains {@code fonepay.com} (Fonepay merchant acquirer domain);</li>
 *   <li>EMVCo country tag {@code 5802NP} (tag 58 = country code, length 02, value "NP");</li>
 *   <li>contains {@code khalti}, {@code nepalpay}, or {@code npqr}.</li>
 * </ul>
 *
 * <h2>Non-misfire guard</h2>
 * ZeroPay domestic QRs carry {@code com.zeropay} and country tag {@code 5802KR}. None of the
 * Nepal markers appear in a ZeroPay QR, so a ZeroPay QR is never classified as Nepal. Matching
 * is case-insensitive for the substring markers; the EMVCo country tag is upper-cased by the
 * spec so {@code 5802NP} is matched case-insensitively for robustness.
 */
public final class NepalQrDetector {

    private NepalQrDetector() {
    }

    /**
     * @param qrPayload raw EMVCo QR string scanned by the wallet (may be null/blank)
     * @return true if the payload looks like a Nepal (Fonepay / NepalPay) QR
     */
    public static boolean isNepal(String qrPayload) {
        if (qrPayload == null || qrPayload.isBlank()) {
            return false;
        }
        String q = qrPayload.toLowerCase(Locale.ROOT);
        return q.contains("fonepay.com")
                || q.contains("5802np")   // EMVCo tag 58 (country) = "NP"
                || q.contains("khalti")
                || q.contains("nepalpay")
                || q.contains("npqr");
    }
}
