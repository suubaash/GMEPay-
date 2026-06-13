package com.gme.sim.scheme.emvco;

/**
 * CRC-16/CCITT (CRC-16-IBM-SDLC variant used by EMVCo QR).
 *
 * Polynomial : 0x1021
 * Initial value: 0xFFFF
 * Input / output NOT reflected (XOR-in MSB first).
 * Final XOR   : 0x0000
 *
 * Known-vector (from EMVCo QR spec appendix):
 *   Input  = "00020101021234560803" (ASCII, 20 chars)
 *   CRC    = 0x6403  (hex "6403")
 *
 * Usage in a QR payload:
 *   1. Build the payload string up to and including the "6304" prefix (tag 63, length 04).
 *   2. Compute CRC over that entire string.
 *   3. Append the 4-char uppercase hex result.
 */
public final class Crc16 {

    private static final int POLY = 0x1021;
    private static final int INIT = 0xFFFF;

    private Crc16() {}

    /**
     * Compute CRC-16/CCITT over the given ASCII string.
     *
     * @param input ASCII string (UTF-8 byte values assumed ≤ 0x7F for all EMVCo fields)
     * @return 16-bit CRC as an unsigned int (0x0000 – 0xFFFF)
     */
    public static int compute(String input) {
        int crc = INIT;
        for (char c : input.toCharArray()) {
            crc ^= (c << 8);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ POLY;
                } else {
                    crc <<= 1;
                }
                crc &= 0xFFFF;
            }
        }
        return crc;
    }

    /**
     * Format CRC as 4-character uppercase hex, e.g. 0x6403 → "6403".
     */
    public static String toHex(int crc) {
        return String.format("%04X", crc & 0xFFFF);
    }

    /**
     * Append the CRC tag (63, length 04) prefix to {@code payloadSoFar},
     * compute CRC over that whole string, then append the 4-hex-char CRC.
     *
     * @param payloadSoFar EMVCo payload built up to (but not including) tag 63
     * @return complete payload string with CRC appended
     */
    public static String appendCrc(String payloadSoFar) {
        // Tag 63, length 04, no value yet — appended so CRC covers it
        String withPrefix = payloadSoFar + "6304";
        int crc = compute(withPrefix);
        return withPrefix + toHex(crc);
    }

    /**
     * Verify the CRC on a complete EMVCo QR payload string.
     * The last 4 chars must equal the CRC of everything before them.
     *
     * @param fullPayload complete QR string including "6304XXXX" suffix
     * @return true if CRC is valid
     */
    public static boolean verify(String fullPayload) {
        if (fullPayload == null || fullPayload.length() < 8) return false;
        String body = fullPayload.substring(0, fullPayload.length() - 4);
        String expected = toHex(compute(body));
        String actual = fullPayload.substring(fullPayload.length() - 4).toUpperCase();
        return expected.equals(actual);
    }
}
