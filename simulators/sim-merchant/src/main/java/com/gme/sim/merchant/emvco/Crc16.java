package com.gme.sim.merchant.emvco;

/**
 * CRC-16/CCITT (the variant used by EMVCo QR): poly 0x1021, init 0xFFFF, no reflection,
 * final XOR 0x0000.
 *
 * <p>Independent copy living in the simulator on purpose (sim is its own oracle); the
 * matching implementation in sim-scheme is verified by its own known-vector test.
 */
public final class Crc16 {

    private static final int POLY = 0x1021;
    private static final int INIT = 0xFFFF;

    private Crc16() {}

    /** Compute CRC-16/CCITT over the given ASCII string. */
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

    /** Format CRC as 4-character uppercase hex, e.g. 0x6403 → "6403". */
    public static String toHex(int crc) {
        return String.format("%04X", crc & 0xFFFF);
    }

    /**
     * Append the CRC tag (63, length 04) prefix to {@code payloadSoFar}, compute CRC over
     * that whole string, then append the 4-hex-char CRC.
     */
    public static String appendCrc(String payloadSoFar) {
        String withPrefix = payloadSoFar + "6304";
        int crc = compute(withPrefix);
        return withPrefix + toHex(crc);
    }

    /** Verify the CRC on a complete EMVCo QR payload string. */
    public static boolean verify(String fullPayload) {
        if (fullPayload == null || fullPayload.length() < 8) return false;
        String body = fullPayload.substring(0, fullPayload.length() - 4);
        String expected = toHex(compute(body));
        String actual = fullPayload.substring(fullPayload.length() - 4).toUpperCase();
        return expected.equals(actual);
    }
}
