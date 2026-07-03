package com.gme.pay.payment.sandbox;

import java.nio.charset.StandardCharsets;

/**
 * CRC-16/CCITT-FALSE checksum used by EMVCo QR tag {@code 63} (polynomial {@code 0x1021},
 * initial value {@code 0xFFFF}, no reflection, no final XOR).
 *
 * <p>Used by the sandbox E2E runner to recompute the tag-63 CRC after it injects an amount
 * field (tag {@code 54}) into a static QR to synthesise a DYNAMIC QR. The result is an
 * upper-case 4-hex-digit string, matching the EMVCo checksum representation.
 */
public final class Crc16Ccitt {

    private Crc16Ccitt() {
    }

    /**
     * Computes the CRC-16/CCITT-FALSE over the UTF-8 bytes of {@code data} and returns it as a
     * 4-character, zero-padded, upper-case hex string (the EMVCo tag-63 value).
     */
    public static String compute(String data) {
        int crc = 0xFFFF;
        for (byte b : data.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
                crc &= 0xFFFF;
            }
        }
        return String.format("%04X", crc);
    }
}
