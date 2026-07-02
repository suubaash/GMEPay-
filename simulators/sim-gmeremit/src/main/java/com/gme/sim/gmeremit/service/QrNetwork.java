package com.gme.sim.gmeremit.service;

/**
 * QR network / payment corridor of a scanned QR payload.
 *
 * <ul>
 *   <li>{@link #DOMESTIC} — a Korean ZeroPay EMVCo QR; pays in KRW via the scheme sim (unchanged).</li>
 *   <li>{@link #NEPAL} — a Nepal Fonepay / NepalPay QR; a GMERemit user "in Nepal" pays in NPR,
 *       decoded via the Nepal QR partner sim.</li>
 * </ul>
 */
public enum QrNetwork {
    DOMESTIC("KRW"),
    NEPAL("NPR");

    private final String currency;

    QrNetwork(String currency) {
        this.currency = currency;
    }

    public String currency() {
        return currency;
    }

    /**
     * Detects the corridor of a scanned QR. A QR is treated as a Nepal payment when it carries a
     * Nepal marker — the Fonepay host ({@code fonepay.com}), a NepalPay host, or the EMVCo
     * country-code tag for Nepal ({@code 5802NP}). Everything else is domestic ZeroPay.
     */
    public static QrNetwork detect(String qrPayload) {
        if (qrPayload == null) {
            return DOMESTIC;
        }
        String q = qrPayload.toLowerCase();
        if (q.contains("fonepay.com")
                || q.contains("nepalpay.com")
                || q.contains("5802np")) {   // EMVCo tag 58 (country) = NP
            return NEPAL;
        }
        return DOMESTIC;
    }
}
