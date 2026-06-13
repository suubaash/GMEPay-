package com.gme.sim.scheme.emvco;

import com.gme.sim.scheme.config.SchemeProfile;
import com.gme.sim.scheme.model.MerchantRecord;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds EMVCo QR payloads (Merchant Presented Mode).
 *
 * Tag layout implemented:
 *   00  Payload Format Indicator  ("01")
 *   01  Point of Initiation       ("11"=STATIC, "12"=DYNAMIC)
 *   29  Merchant Account Information (sub-TLV with GUID + merchant-id)
 *   52  Merchant Category Code
 *   53  Transaction Currency (numeric ISO-4217)
 *   54  Transaction Amount   (only for DYNAMIC)
 *   58  Country Code
 *   59  Merchant Name
 *   60  Merchant City
 *   63  CRC (4-hex uppercase, covers everything including "6304" prefix)
 */
public final class EmvcoQrEncoder {

    private EmvcoQrEncoder() {}

    /**
     * Build a STATIC QR payload (tag 01 = "11", no amount).
     */
    public static String buildStatic(MerchantRecord merchant, SchemeProfile profile) {
        return build(merchant, profile, null);
    }

    /**
     * Build a DYNAMIC QR payload (tag 01 = "12", amount embedded).
     *
     * @param amount transaction amount, serialized as plain decimal string
     */
    public static String buildDynamic(MerchantRecord merchant, SchemeProfile profile,
                                      BigDecimal amount) {
        return build(merchant, profile, amount);
    }

    private static String build(MerchantRecord merchant, SchemeProfile profile,
                                 BigDecimal amount) {
        List<TlvField> fields = new ArrayList<>();

        // Tag 00 – Payload Format Indicator (always "01")
        fields.add(new TlvField(0, "01"));

        // Tag 01 – Point of Initiation Method
        fields.add(new TlvField(1, amount == null ? "11" : "12"));

        // Tag 29 – Merchant Account Information (scheme-specific sub-TLV)
        String merchantAccountInfo = buildMerchantAccountInfo(profile, merchant.merchantId());
        fields.add(new TlvField(profile.merchantAccountTag, merchantAccountInfo));

        // Tag 52 – Merchant Category Code
        fields.add(new TlvField(52, merchant.mcc()));

        // Tag 53 – Transaction Currency (numeric)
        fields.add(new TlvField(53, profile.numericCurrencyCode));

        // Tag 54 – Transaction Amount (DYNAMIC only)
        if (amount != null) {
            fields.add(new TlvField(54, amount.toPlainString()));
        }

        // Tag 58 – Country Code
        fields.add(new TlvField(58, profile.countryCode));

        // Tag 59 – Merchant Name (max 25 chars per spec)
        fields.add(new TlvField(59, truncate(merchant.name(), 25)));

        // Tag 60 – Merchant City (max 15 chars per spec)
        fields.add(new TlvField(60, truncate(merchant.city(), 15)));

        // Assemble payload without CRC
        StringBuilder sb = new StringBuilder();
        for (TlvField f : fields) {
            sb.append(f.encode());
        }

        // Append CRC (tag 63, length 04, 4-hex value)
        return Crc16.appendCrc(sb.toString());
    }

    /**
     * Sub-TLV for the merchant account info block (nested inside tag 29).
     * Tag 00 = GUID / scheme AID  (e.g. "com.khqr")
     * Tag 01 = Merchant ID
     */
    private static String buildMerchantAccountInfo(SchemeProfile profile, String merchantId) {
        String guid = "com." + profile.schemeId.toLowerCase();
        TlvField guidField = new TlvField(0, guid);
        TlvField midField  = new TlvField(1, merchantId);
        return guidField.encode() + midField.encode();
    }

    /**
     * Parse the merchant-id back out of a complete QR payload.
     * Finds tag 29 (or the profile's merchantAccountTag), then tag 01 inside.
     *
     * @throws IllegalArgumentException if the payload is malformed or CRC invalid
     */
    public static String extractMerchantId(String payload, SchemeProfile profile)
            throws IllegalArgumentException {
        if (!Crc16.verify(payload)) {
            throw new IllegalArgumentException("CRC invalid");
        }
        String body = payload.substring(0, payload.length() - 8); // strip 63XX + crc
        String mai = extractTagValue(body, profile.merchantAccountTag);
        if (mai == null) {
            throw new IllegalArgumentException(
                    "Tag " + profile.merchantAccountTag + " not found");
        }
        String merchantId = extractTagValue(mai, 1);
        if (merchantId == null) {
            throw new IllegalArgumentException("Merchant-id sub-tag 01 not found");
        }
        return merchantId;
    }

    /**
     * Extract the value of a top-level decimal tag from a TLV string.
     * Returns null if the tag is not found.
     */
    public static String extractTagValue(String tlv, int tag) {
        int i = 0;
        while (i + 4 <= tlv.length()) {
            int t, l;
            try {
                t = Integer.parseInt(tlv.substring(i, i + 2));
                l = Integer.parseInt(tlv.substring(i + 2, i + 4));
            } catch (NumberFormatException e) {
                break;
            }
            if (i + 4 + l > tlv.length()) break;
            String v = tlv.substring(i + 4, i + 4 + l);
            if (t == tag) return v;
            i += 4 + l;
        }
        return null;
    }

    /**
     * Extract tag 01 (point-of-initiation) value from a full QR payload.
     * Returns "11" for static, "12" for dynamic.
     */
    public static String extractInitiationMode(String payload) {
        // Strip CRC suffix before walking
        String body = payload.endsWith("6304") ? payload
                : payload.substring(0, Math.max(0, payload.length() - 8));
        return extractTagValue(payload, 1); // works because 63 tag comes last
    }

    /**
     * Extract tag 54 (amount) from a DYNAMIC payload.
     * Returns null if absent (i.e. static).
     */
    public static BigDecimal extractAmount(String payload) {
        String raw = extractTagValue(payload, 54);
        return raw == null ? null : new BigDecimal(raw);
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : s.length() > max ? s.substring(0, max) : s;
    }
}
