package com.gme.sim.nepalqr.qr;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Best-effort EMVCo / Fonepay TLV parser.
 *
 * EMVCo QR = concatenation of TLV fields: 2-digit tag + 2-digit length + value.
 * Sample Fonepay QR (from the docs):
 *   00020101021126350011fonepay.com0716...5914SudanMerchant6015AathraiTriveni...6304d60f
 *
 * Tags read:
 *   00 Payload Format Indicator
 *   01 Point of Initiation Method (11=static, 12=dynamic)
 *   26-51 Merchant Account Information templates (nested TLV; 26 = fonepay when GUID="fonepay.com")
 *   52 Merchant Category Code
 *   53 Transaction Currency (numeric ISO-4217; 524 = NPR)
 *   54 Transaction Amount (rupees, dynamic only)
 *   58 Country Code
 *   59 Merchant Name
 *   60 Merchant City
 *   62 Additional Data (nested; 07 = purpose/reference)
 *
 * Any tag not present falls back to a mock default in {@link QrParseResult}.
 */
public final class QrParser {

    private QrParser() {}

    /** @return true if the string looks like a parseable EMVCo/JSON QR. */
    public static boolean looksValid(String qs) {
        if (qs == null) return false;
        String s = qs.trim();
        if (s.length() < 8) return false;
        // JSON-encoded (khalti / mobank)
        if (s.startsWith("{")) return true;
        // EMVCo starts with payload format indicator "0002xx"
        return s.startsWith("0002");
    }

    public static QrParseResult parse(String qsRaw) {
        QrParseResult r = new QrParseResult();
        if (qsRaw == null) return r;
        String qs = qsRaw.trim();

        Map<String, String> top = walk(qs);
        r.tags = top;

        // Point of initiation
        String poi = top.get("01");
        r.initMethod = "12".equals(poi) ? "dynamic" : "static";

        // MCC
        if (top.containsKey("52")) r.merchantCategoryCode = top.get("52");

        // Currency (numeric ISO 4217 -> alpha). 524 = NPR.
        String cur = top.get("53");
        if ("524".equals(cur) || cur == null) r.trxCurrency = "NPR";
        else r.trxCurrency = cur; // leave numeric if not NPR; sim only really cares about NPR

        // Amount (rupees) -> paisa
        String amt = top.get("54");
        if (amt != null && !amt.isBlank()) {
            try {
                BigDecimal rupees = new BigDecimal(amt.trim());
                r.amountPaisa = rupees.movePointRight(2).longValueExact();
            } catch (RuntimeException ignored) { /* keep null (static) */ }
        }

        // Country
        if (top.containsKey("58")) r.merchantCountry = top.get("58");
        // Merchant name / city
        if (top.containsKey("59")) r.merchantName = top.get("59");
        if (top.containsKey("60")) r.merchantCity = top.get("60");

        // Merchant Account Information templates (26..51) -> merchant id + network
        for (int tag = 26; tag <= 51; tag++) {
            String key = String.format("%02d", tag);
            String mai = top.get(key);
            if (mai == null) continue;
            Map<String, String> sub = walk(mai);
            String guid = sub.get("00");
            if (guid != null) {
                r.network = networkFromGuid(guid);
                r.merchantInfoExtra = guid;
            }
            // Sub-tag 01 (or first non-GUID) commonly carries the merchant id.
            String mid = sub.get("01");
            if (mid == null) {
                for (Map.Entry<String, String> e : sub.entrySet()) {
                    if (!"00".equals(e.getKey())) { mid = e.getValue(); break; }
                }
            }
            if (mid != null && !mid.isBlank()) r.merchantId = mid;
            break; // first MAI template wins
        }

        // Additional data field 62 -> purpose (sub-tag 07 = reference label)
        String add = top.get("62");
        if (add != null) {
            Map<String, String> sub = walk(add);
            String p = sub.get("08"); // purpose of transaction
            if (p != null && !p.isBlank()) r.purpose = p;
        }

        return r;
    }

    private static String networkFromGuid(String guid) {
        String g = guid.toLowerCase();
        if (g.contains("fonepay")) return "fonepay";
        if (g.contains("nepalpay")) return "nepalpay";
        if (g.contains("unionpay") || g.contains("cup")) return "unionpay";
        if (g.contains("smartqr") || g.contains("smart")) return "smartqr";
        return "fonepay";
    }

    /**
     * Walk a TLV string into an ordered tag->value map. Tolerant of trailing junk:
     * stops when it can no longer read a full field.
     *
     * Some real-world (and doc-sample) QRs carry a declared length that is off by
     * one for text fields such as merchant name/city. To stay robust we look ahead:
     * after consuming a field, if the remainder does NOT start with a plausible
     * 2-digit tag + 2-digit length header but a length-1 value WOULD, we back off
     * by one character. This keeps subsequent tags in sync.
     */
    public static Map<String, String> walk(String tlv) {
        Map<String, String> out = new LinkedHashMap<>();
        if (tlv == null) return out;
        int i = 0;
        while (i + 4 <= tlv.length()) {
            String tag = tlv.substring(i, i + 2);
            int len;
            try {
                len = Integer.parseInt(tlv.substring(i + 2, i + 4));
            } catch (NumberFormatException e) {
                break;
            }
            int valStart = i + 4;
            int valEnd = valStart + len;
            if (valEnd > tlv.length()) {
                // Declared length overruns; back off to fit what remains.
                valEnd = tlv.length();
            }
            // Resync heuristic for doc/real QRs whose text-field lengths run one
            // char long. Back off by one when either:
            //  (a) the declared length leaves the remainder NOT looking like a
            //      valid next header, but length-1 does; or
            //  (b) this is a free-text tag (merchant name/city, 59/60) whose value
            //      would end in a digit that is really the next tag's first char,
            //      and length-1 still leaves a valid header.
            boolean textTag = "59".equals(tag) || "60".equals(tag);
            if (len >= 1 && valEnd < tlv.length() && looksLikeHeader(tlv, valEnd - 1)) {
                boolean headerBad = !looksLikeHeader(tlv, valEnd);
                boolean textEndsDigit = textTag && Character.isDigit(tlv.charAt(valEnd - 1));
                if (headerBad || textEndsDigit) {
                    valEnd -= 1;
                }
            }
            out.putIfAbsent(tag, tlv.substring(valStart, valEnd));
            i = valEnd;
        }
        return out;
    }

    /** True if position {@code p} begins a plausible TLV header (tag + length both 2 digits). */
    private static boolean looksLikeHeader(String s, int p) {
        if (p + 4 > s.length()) return p == s.length(); // clean end is fine
        for (int k = p; k < p + 4; k++) {
            if (!Character.isDigit(s.charAt(k))) return false;
        }
        return true;
    }
}
