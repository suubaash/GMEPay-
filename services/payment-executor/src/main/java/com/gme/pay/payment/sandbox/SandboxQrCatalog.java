package com.gme.pay.payment.sandbox;

import java.util.Locale;

/**
 * Well-known sandbox merchant QR payloads used by the E2E runner, keyed by (country, MPM type).
 *
 * <p>The STATIC QRs are canonical EMVCo TLV strings the {@code QrSchemeClassifier} recognises
 * (Nepal Fonepay / Korea ZeroPay). A DYNAMIC QR is derived from the STATIC one by inserting an
 * EMVCo amount field (tag {@code 54}) immediately before the CRC field (tag {@code 63}) and
 * recomputing the tag-63 CRC-16/CCITT so the payload stays checksum-valid.
 */
public final class SandboxQrCatalog {

    /** Nepal (Fonepay) static merchant QR — network {@code fonepay.com}, country NP. */
    public static final String NP_STATIC =
            "00020101021126150011fonepay.com5802NP5910KINAUN PVT6304ABCD";

    /** Korea (ZeroPay) static merchant QR — network {@code com.zeropay}, country KR. */
    public static final String KR_STATIC =
            "00020101021126260011com.zeropay010888888885802KR5910COFFEE HUT6304ABCD";

    private SandboxQrCatalog() {
    }

    /**
     * Resolves the QR payload for a country + MPM type.
     *
     * @param country ISO-3166 alpha-2, {@code NP} or {@code KR} (case-insensitive)
     * @param mpmType {@code STATIC} or {@code DYNAMIC} (case-insensitive)
     * @param amount  amount to embed (tag 54) for DYNAMIC; ignored for STATIC
     * @return the resolved EMVCo QR string
     * @throws IllegalArgumentException if the country is unknown
     */
    public static String resolve(String country, String mpmType, String amount) {
        String base = staticFor(country);
        if (mpmType != null && "DYNAMIC".equalsIgnoreCase(mpmType.trim())) {
            return toDynamic(base, amount);
        }
        return base;
    }

    /** The static QR for a country. */
    public static String staticFor(String country) {
        String c = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
        return switch (c) {
            case "NP" -> NP_STATIC;
            case "KR" -> KR_STATIC;
            default -> throw new IllegalArgumentException(
                    "Unsupported sandbox country: " + country + " (supported: NP, KR)");
        };
    }

    /**
     * Builds a DYNAMIC QR from a static one: strips the existing CRC field (tag {@code 63}),
     * appends an amount field (tag {@code 54} = {@code amount}), re-appends the tag-63 header
     * ({@code "6304"}) and recomputes the CRC-16/CCITT over everything up to and including that
     * header (per EMVCo §4).
     */
    public static String toDynamic(String staticQr, String amount) {
        String amt = amount == null ? "0" : amount.trim();
        int crcIdx = staticQr.lastIndexOf("6304");
        String withoutCrc = crcIdx >= 0 ? staticQr.substring(0, crcIdx) : staticQr;

        String amountField = "54" + twoDigitLen(amt) + amt;
        // CRC is computed over the data INCLUDING the "6304" tag-63 header, excluding the value.
        String toChecksum = withoutCrc + amountField + "6304";
        String crc = Crc16Ccitt.compute(toChecksum);
        return toChecksum + crc;
    }

    /** EMVCo two-digit length prefix (values here are short, always &lt; 100 chars). */
    private static String twoDigitLen(String value) {
        int len = value.length();
        return String.format("%02d", len);
    }
}
