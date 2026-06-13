package com.gme.sim.scheme.emvco;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Customer-Presented Mode (CPM) token builder.
 *
 * The CPM token encodes the customer's payment credential in an EMVCo
 * Customer-Presented QR format.  Sub-TLV inside tag 64 (Application Template):
 *   Tag 4F – Application Identifier (AID) — e.g. "KHQRCPM"
 *   Tag 50 – Application Label         — e.g. customer ID
 *   Tag 9F10 – Issuer Application Data  — funding reference
 *
 * The outer TLV has:
 *   Tag 85 – Payload Format Indicator ("CPM01")
 *   Tag 64 – Application Template (sub-TLV above)
 *
 * Final token = Base64-encode(TLV wire bytes) using standard Base64.
 *
 * Note: EMVCo CPM uses BER-TLV (binary), but for a dev simulator we
 * encode the same decimal-tag / ASCII scheme used in MPM for simplicity,
 * then Base64 the UTF-8 bytes — this is clearly documented and sufficient
 * for integration testing.
 */
public final class CpmTokenBuilder {

    /** CPM decimal-tag constants */
    private static final int TAG_PAYLOAD_FORMAT = 85;  // 0x55
    private static final int TAG_APP_TEMPLATE    = 64;  // 0x40
    private static final int TAG_AID             = 4;   // 0x04 sub-TLV
    private static final int TAG_APP_LABEL       = 5;   // 0x05 sub-TLV
    private static final int TAG_ISSUER_DATA     = 9;   // 0x09 sub-TLV (funding ref)

    private CpmTokenBuilder() {}

    /**
     * Build and Base64-encode a CPM token for the given customer.
     *
     * @param aid        scheme AID, e.g. "KHQRCPM"
     * @param customerId customer identifier
     * @param fundingRef opaque funding source reference
     * @return Base64-encoded TLV string
     */
    public static String build(String aid, String customerId, String fundingRef) {
        // Inner application template sub-TLV
        String appTemplate =
                new TlvField(TAG_AID, aid).encode() +
                new TlvField(TAG_APP_LABEL, customerId).encode() +
                new TlvField(TAG_ISSUER_DATA, fundingRef).encode();

        // Outer payload
        String payload =
                new TlvField(TAG_PAYLOAD_FORMAT, "CPM01").encode() +
                new TlvField(TAG_APP_TEMPLATE, appTemplate).encode();

        return Base64.getEncoder().encodeToString(
                payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decode and extract the customerId from a CPM token.
     *
     * @param base64Token the token returned by {@link #build}
     * @return customerId embedded in the token, or null if not decodeable
     */
    public static String extractCustomerId(String base64Token) {
        try {
            String payload = new String(
                    Base64.getDecoder().decode(base64Token), StandardCharsets.UTF_8);
            // Walk to tag TAG_APP_TEMPLATE, then look inside for TAG_APP_LABEL
            String appTemplate = EmvcoQrEncoder.extractTagValue(payload, TAG_APP_TEMPLATE);
            if (appTemplate == null) return null;
            return EmvcoQrEncoder.extractTagValue(appTemplate, TAG_APP_LABEL);
        } catch (Exception e) {
            return null;
        }
    }
}
