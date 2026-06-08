package com.gme.pay.qr.domain.emvco;

import com.gme.pay.qr.exception.QRInvalidChecksumException;
import com.gme.pay.qr.exception.QRMalformedException;

/**
 * CRC-16/CCITT checksum verifier for EMVCo QR payloads (tag 63).
 *
 * <p>Algorithm: polynomial 0x1021, initial value 0xFFFF, no input/output reflection, no final XOR.
 * The checksum covers all characters of the payload up to and including the tag and length of
 * tag 63 (i.e. the check string ends with "6304").
 */
public final class EMVCoCrcVerifier {

    /** Tag identifier for the CRC field in EMVCo QR. */
    private static final String TAG_63 = "63";
    /** Expected length field value for tag 63 (always 4 hex digits). */
    private static final String TAG_63_LEN = "04";
    /** CRC-16/CCITT polynomial. */
    private static final int POLYNOMIAL = 0x1021;
    /** CRC-16/CCITT initial value. */
    private static final int INIT = 0xFFFF;

    private EMVCoCrcVerifier() {}

    /**
     * Compute the CRC-16/CCITT checksum of {@code data} and return it as a 4-char uppercase hex
     * string.
     *
     * @param data the string to checksum (processed byte-by-byte as UTF-8 / ASCII)
     * @return 4-char uppercase hex CRC value, e.g. "A60A"
     */
    public static String compute(String data) {
        int crc = INIT;
        for (char c : data.toCharArray()) {
            crc ^= (c & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = ((crc << 1) ^ POLYNOMIAL) & 0xFFFF;
                } else {
                    crc = (crc << 1) & 0xFFFF;
                }
            }
        }
        return String.format("%04X", crc);
    }

    /**
     * Verify the CRC embedded in the full EMVCo QR payload.
     *
     * <p>The payload must end with "6304" followed by exactly 4 hex characters that are the CRC
     * value over the preceding portion (everything up to and including "6304").
     *
     * @param fullPayload the complete QR payload string including the CRC tag
     * @return {@code true} if the checksum matches
     * @throws QRMalformedException       if tag 63 is absent from the payload
     * @throws QRInvalidChecksumException if tag 63 is present but the CRC does not match
     */
    public static boolean verify(String fullPayload) {
        String tag63Prefix = TAG_63 + TAG_63_LEN;
        int tag63Pos = fullPayload.indexOf(tag63Prefix);
        if (tag63Pos < 0) {
            throw new QRMalformedException("Tag 63 (CRC) is absent from the QR payload");
        }

        // The CRC covers everything up to and including "6304"
        int crcCoverEnd = tag63Pos + tag63Prefix.length();
        String dataToCheck = fullPayload.substring(0, crcCoverEnd);

        // Extract the 4-char CRC value immediately following "6304"
        if (fullPayload.length() < crcCoverEnd + 4) {
            throw new QRMalformedException("Tag 63 value is truncated in the QR payload");
        }
        String embeddedCrc = fullPayload.substring(crcCoverEnd, crcCoverEnd + 4).toUpperCase();

        String computedCrc = compute(dataToCheck);
        if (!computedCrc.equals(embeddedCrc)) {
            throw new QRInvalidChecksumException(
                    "CRC mismatch: expected " + computedCrc + " but payload contains " + embeddedCrc);
        }
        return true;
    }
}
