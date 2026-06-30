package com.gme.pay.qr.domain.cpm;

import com.gme.pay.qr.domain.emvco.EMVCoCrcVerifier;

/**
 * Encodes a CPM {@code prepare_token} into an EMVCo-style TLV envelope (WBS 5.4-T11).
 *
 * <p>Layout (mirrors the MPM TLV grammar so {@link CpmPayloadParser} can decode it with the
 * shared {@code EMVCoTlvParser}):
 * <pre>
 *   00 02 01            payload format indicator = "01"
 *   85 LL &lt;CPM MAI&gt;     CPM template (tag 85), itself TLV:
 *                          00 LL &lt;reverse-domain "GME.CPM"&gt;
 *                          01 LL &lt;prepareToken&gt;
 *                          02 LL &lt;schemeId&gt;
 *   63 04 &lt;CRC&gt;          CRC-16/CCITT over everything up to and including "6304"
 * </pre>
 * The CPM template tag 85 keeps CPM payloads distinguishable from MPM MAI slots (26-51).
 */
public final class CpmPayloadEncoder {

    /** EMVCo top-level tag used for the CPM template in this envelope. */
    public static final int CPM_TEMPLATE_TAG = 85;
    /** CPM template sub-tag carrying the reverse-domain identifier. */
    public static final int SUB_TAG_DOMAIN = 0;
    /** CPM template sub-tag carrying the prepare token. */
    public static final int SUB_TAG_TOKEN = 1;
    /** CPM template sub-tag carrying the scheme id. */
    public static final int SUB_TAG_SCHEME = 2;

    private static final String REVERSE_DOMAIN = "GME.CPM";

    private CpmPayloadEncoder() {}

    /**
     * Encode a CPM prepare token + scheme id into a CRC-protected TLV envelope.
     *
     * @param prepareToken the opaque scheme prepare token; never blank
     * @param schemeId     the scheme identifier; never blank
     * @return the full CPM QR content string including the tag-63 CRC
     */
    public static String encode(String prepareToken, String schemeId) {
        if (prepareToken == null || prepareToken.isBlank()) {
            throw new IllegalArgumentException("prepareToken must not be blank");
        }
        if (schemeId == null || schemeId.isBlank()) {
            throw new IllegalArgumentException("schemeId must not be blank");
        }
        String template = tlv(SUB_TAG_DOMAIN, REVERSE_DOMAIN)
                + tlv(SUB_TAG_TOKEN, prepareToken)
                + tlv(SUB_TAG_SCHEME, schemeId);

        String body = tlv(0, "01") + tlv(CPM_TEMPLATE_TAG, template);
        String withCrcPrefix = body + "6304";
        return withCrcPrefix + EMVCoCrcVerifier.compute(withCrcPrefix);
    }

    /** Emit one TAG(2)+LEN(2)+VALUE data object. Length is capped at EMVCo's 2-digit field. */
    private static String tlv(int tag, String value) {
        if (value.length() > 99) {
            throw new IllegalArgumentException(
                    "TLV value for tag " + tag + " exceeds 99 chars: " + value.length());
        }
        return String.format("%02d%02d%s", tag, value.length(), value);
    }
}
