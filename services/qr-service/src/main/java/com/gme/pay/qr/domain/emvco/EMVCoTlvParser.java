package com.gme.pay.qr.domain.emvco;

import com.gme.pay.qr.exception.QRMalformedException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stateless utility that tokenises EMVCo QR TLV payloads.
 *
 * <p>Each data object is encoded as: TAG(2 ASCII digits) + LENGTH(2 ASCII decimal digits) +
 * VALUE(LENGTH chars). Tags 26-51 are Merchant Account Information (MAI) templates; their values
 * are themselves TLV sequences and can be decoded with {@link #parseTemplate(String)}.
 */
public final class EMVCoTlvParser {

    private EMVCoTlvParser() {}

    /**
     * Parse the top-level TLV string and return an ordered map of tag -> value.
     *
     * @throws QRMalformedException if the payload is blank, a declared length exceeds remaining
     *     chars, or a duplicate tag appears at the same level.
     */
    public static Map<Integer, String> parseTopLevel(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new QRMalformedException("QR payload must not be null or blank");
        }
        return parseTlv(payload);
    }

    /**
     * Parse a MAI template string (sub-TLV inside a MAI slot).
     *
     * @throws QRMalformedException same conditions as {@link #parseTopLevel(String)}.
     */
    public static Map<Integer, String> parseTemplate(String templateValue) {
        if (templateValue == null || templateValue.isBlank()) {
            throw new QRMalformedException("MAI template value must not be null or blank");
        }
        return parseTlv(templateValue);
    }

    // -----------------------------------------------------------------------
    // Internal
    // -----------------------------------------------------------------------

    private static Map<Integer, String> parseTlv(String data) {
        Map<Integer, String> result = new LinkedHashMap<>();
        int pos = 0;
        while (pos < data.length()) {
            if (pos + 4 > data.length()) {
                throw new QRMalformedException(
                        "TLV truncated: need at least 4 chars for tag+length at position " + pos);
            }
            String tagStr = data.substring(pos, pos + 2);
            String lenStr = data.substring(pos + 2, pos + 4);

            int tag;
            int len;
            try {
                tag = Integer.parseInt(tagStr);
            } catch (NumberFormatException e) {
                throw new QRMalformedException("Non-numeric tag '" + tagStr + "' at position " + pos);
            }
            try {
                len = Integer.parseInt(lenStr);
            } catch (NumberFormatException e) {
                throw new QRMalformedException("Non-numeric length '" + lenStr + "' at position " + (pos + 2));
            }

            int valueStart = pos + 4;
            int valueEnd = valueStart + len;
            if (valueEnd > data.length()) {
                throw new QRMalformedException(
                        "Declared length " + len + " exceeds remaining data at position " + pos);
            }
            String value = data.substring(valueStart, valueEnd);

            if (result.containsKey(tag)) {
                throw new QRMalformedException("Duplicate tag " + tagStr + " at position " + pos);
            }
            result.put(tag, value);
            pos = valueEnd;
        }
        return result;
    }
}
