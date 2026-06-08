package com.gme.pay.qr.domain.emvco.helper;

import com.gme.pay.qr.domain.emvco.EMVCoCrcVerifier;

/**
 * Test helper that builds valid EMVCo TLV QR payload strings with correct CRC appended.
 *
 * <p>Usage:
 * <pre>{@code
 *   String payload = new TlvBuilder()
 *       .addTag(0, "01")
 *       .addTag(52, "5411")
 *       .addTag(53, "410")
 *       .addTag(58, "KR")
 *       .addTag(59, "TestMerchant")
 *       .addTag(60, "Seoul")
 *       .addMai(26, "M123456789", "QR00000000000000000001")
 *       .build();
 * }</pre>
 */
public class TlvBuilder {

    private final StringBuilder body = new StringBuilder();

    /** Add a top-level tag with the given string value. */
    public TlvBuilder addTag(int tag, String value) {
        body.append(String.format("%02d%02d%s", tag, value.length(), value));
        return this;
    }

    /**
     * Add a MAI (Merchant Account Information) slot at {@code maiTag} containing
     * sub-tag 01 (merchantId) and sub-tag 02 (qrCodeId).
     */
    public TlvBuilder addMai(int maiTag, String merchantId, String qrCodeId) {
        String sub01 = String.format("01%02d%s", merchantId.length(), merchantId);
        String sub02 = String.format("02%02d%s", qrCodeId.length(), qrCodeId);
        String template = sub01 + sub02;
        addTag(maiTag, template);
        return this;
    }

    /**
     * Build the final payload: append tag 63 header ("6304"), compute CRC-16/CCITT over
     * everything up to and including "6304", then append the 4-char hex CRC value.
     *
     * @return a complete, CRC-correct TLV payload string
     */
    public String build() {
        String tag63Header = "6304";
        String dataToCheck = body.toString() + tag63Header;
        String crc = EMVCoCrcVerifier.compute(dataToCheck);
        return dataToCheck + crc;
    }
}
