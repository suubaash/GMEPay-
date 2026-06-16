package com.gme.sim.merchant.emvco;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds spec-faithful ZeroPay EMVCo Merchant-Presented-Mode (MPM) QR payloads.
 *
 * <p>Distinct from the generic {@code EmvcoQrEncoder} in sim-scheme: this encoder carries
 * the ZeroPay-specific fields that the {@code 420000}/{@code 500000} 전문 echo
 * (등록기관ID, 가맹점ID, 거래일련번호, 체크문자) inside the merchant-account-information
 * template, and always uses KRW numeric currency {@code 410}.
 *
 * <p>EMVCo tag layout:
 * <pre>
 *   00  Payload Format Indicator        "01"
 *   01  Point of Initiation Method      "11"=static (QR구분 1), "12"=dynamic (QR구분 2)
 *   30  Merchant Account Info (ZeroPay) sub-TLV:
 *         00 GUID (ZeroPay AID)
 *         01 등록기관ID (QR registrar id)
 *         02 가맹점ID  (merchant id)
 *         03 거래일련번호 (dynamic only)
 *         04 체크문자     (dynamic only)
 *   52  Merchant Category Code
 *   53  Transaction Currency            "410" (KRW)
 *   54  Transaction Amount              (dynamic only)
 *   58  Country Code                    "KR"
 *   59  Merchant Name                   (≤25)
 *   60  Merchant City                   (≤15)
 *   63  CRC-16/CCITT
 * </pre>
 *
 * <p>Where the public ZeroPay QR spec excerpt did not pin a sub-tag number, the choice here
 * is illustrative but internally consistent (and round-trips with {@link #extract}).
 */
public final class ZeroPayQrBuilder {

    private ZeroPayQrBuilder() {}

    /** ZeroPay merchant-account-information template tag. */
    public static final int TAG_MERCHANT_ACCOUNT = 30;
    /** Illustrative ZeroPay scheme AID (GUID sub-tag 00). */
    public static final String ZEROPAY_AID = "A000000677010111";
    /** KRW numeric currency (EMVCo tag 53). */
    public static final String KRW_NUMERIC = "410";
    public static final String COUNTRY_KR = "KR";

    public static final String QR_DIV_STATIC = "1";
    public static final String QR_DIV_DYNAMIC = "2";

    /**
     * Result of building a ZeroPay QR — both the payload and the discrete fields the
     * downstream 전문 needs, so the terminal can show the QR↔전문 linkage without re-parsing.
     *
     * @param qrPayload    full EMVCo string with CRC
     * @param qrDivision   "1" (static) | "2" (dynamic)  — maps to 전문 field 34
     * @param registrarId  등록기관ID (field 35)
     * @param merchantId   가맹점ID (field 38)
     * @param qrSerial     거래일련번호 (field 36); empty for static
     * @param checkChar    체크문자 (field 37); empty for static
     * @param amountKrw    embedded amount, or null for static
     */
    public record QrResult(
            String qrPayload, String qrDivision, String registrarId,
            String merchantId, String qrSerial, String checkChar, Long amountKrw) {}

    /** Build a static store QR (QR구분 "1", no amount, no per-txn serial). */
    public static QrResult buildStatic(String registrarId, String merchantId,
                                       String mcc, String merchantName, String city) {
        return build(false, registrarId, merchantId, "", mcc, merchantName, city, null);
    }

    /**
     * Build a dynamic charge QR (QR구분 "2", amount embedded).
     *
     * @param qrSerial 거래일련번호 — the per-transaction serial encoded into the QR
     */
    public static QrResult buildDynamic(String registrarId, String merchantId, String qrSerial,
                                        String mcc, String merchantName, String city, long amountKrw) {
        return build(true, registrarId, merchantId, qrSerial, mcc, merchantName, city, amountKrw);
    }

    private static QrResult build(boolean dynamic, String registrarId, String merchantId,
                                  String qrSerial, String mcc, String merchantName, String city,
                                  Long amountKrw) {
        String div = dynamic ? QR_DIV_DYNAMIC : QR_DIV_STATIC;
        String checkChar = dynamic ? checkChar(registrarId, merchantId, qrSerial, amountKrw) : "";

        List<TlvField> fields = new ArrayList<>();
        fields.add(new TlvField(0, "01"));
        fields.add(new TlvField(1, dynamic ? "12" : "11"));
        fields.add(new TlvField(TAG_MERCHANT_ACCOUNT,
                merchantAccountInfo(registrarId, merchantId, qrSerial, checkChar, dynamic)));
        fields.add(new TlvField(52, mcc));
        fields.add(new TlvField(53, KRW_NUMERIC));
        if (dynamic) {
            fields.add(new TlvField(54, Long.toString(amountKrw)));
        }
        fields.add(new TlvField(58, COUNTRY_KR));
        fields.add(new TlvField(59, truncate(merchantName, 25)));
        fields.add(new TlvField(60, truncate(city, 15)));

        StringBuilder sb = new StringBuilder();
        for (TlvField f : fields) sb.append(f.encode());
        String payload = Crc16.appendCrc(sb.toString());

        return new QrResult(payload, div, registrarId, merchantId,
                dynamic ? qrSerial : "", checkChar, amountKrw);
    }

    private static String merchantAccountInfo(String registrarId, String merchantId,
                                              String qrSerial, String checkChar, boolean dynamic) {
        StringBuilder sb = new StringBuilder();
        sb.append(new TlvField(0, ZEROPAY_AID).encode());
        sb.append(new TlvField(1, registrarId).encode());
        sb.append(new TlvField(2, merchantId).encode());
        if (dynamic) {
            sb.append(new TlvField(3, qrSerial).encode());
            sb.append(new TlvField(4, checkChar).encode());
        }
        return sb.toString();
    }

    /**
     * Derive a deterministic 4-char 체크문자 from the QR's discriminating fields, so the
     * same inputs always produce the same check character (test-stable, no clock/RNG).
     */
    public static String checkChar(String registrarId, String merchantId,
                                   String qrSerial, Long amountKrw) {
        String basis = nz(registrarId) + "|" + nz(merchantId) + "|" + nz(qrSerial)
                + "|" + (amountKrw == null ? "" : amountKrw);
        return Crc16.toHex(Crc16.compute(basis));
    }

    /**
     * Parse a ZeroPay QR back into its discrete fields (verifies CRC first).
     *
     * @throws IllegalArgumentException on bad CRC or a missing merchant-account template
     */
    public static QrResult extract(String payload) {
        if (!Crc16.verify(payload)) {
            throw new IllegalArgumentException("CRC invalid");
        }
        String init = tagValue(payload, 1);
        boolean dynamic = "12".equals(init);
        String mai = tagValue(payload, TAG_MERCHANT_ACCOUNT);
        if (mai == null) {
            throw new IllegalArgumentException("Tag " + TAG_MERCHANT_ACCOUNT + " not found");
        }
        String registrarId = nz(tagValue(mai, 1));
        String merchantId = nz(tagValue(mai, 2));
        String serial = nz(tagValue(mai, 3));
        String check = nz(tagValue(mai, 4));
        String amountRaw = tagValue(payload, 54);
        Long amount = amountRaw == null ? null : Long.parseLong(amountRaw);
        return new QrResult(payload, dynamic ? QR_DIV_DYNAMIC : QR_DIV_STATIC,
                registrarId, merchantId, serial, check, amount);
    }

    /** Extract the value of a top-level decimal tag from a TLV string; null if absent. */
    static String tagValue(String tlv, int tag) {
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

    private static String truncate(String s, int max) {
        return s == null ? "" : s.length() > max ? s.substring(0, max) : s;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
