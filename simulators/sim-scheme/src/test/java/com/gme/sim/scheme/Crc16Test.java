package com.gme.sim.scheme;

import com.gme.sim.scheme.emvco.Crc16;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CRC-16/CCITT known-vector tests.
 *
 * Vector 1 (computed, cross-checked against the algorithm):
 *   Body   = "00020101021234560803"   (20 ASCII chars, a minimal EMVCo payload body)
 *   With tag-63 prefix: "000201010212345608036304"
 *   Expected CRC-16/CCITT (poly 0x1021, init 0xFFFF) = 0x6AE6 → "6AE6"
 *
 * Vector 2: round-trip appendCrc → verify
 */
class Crc16Test {

    /** Known-vector: CRC-16/CCITT over the minimal EMVCo body + "6304" prefix. */
    @Test
    void knownVector_emvcoAppendix() {
        // Computed: CRC-16/CCITT (poly 0x1021, init 0xFFFF, no reflection)
        // over "00020101021234560803" + "6304"  =  "000201010212345608036304"
        // = 0x6AE6
        String body = "000201010212345608036304";
        int crc = Crc16.compute(body);
        assertEquals("6AE6", Crc16.toHex(crc),
                "CRC-16/CCITT known-vector must equal 6AE6");
    }

    /** appendCrc appends "6304" prefix then the computed CRC. */
    @Test
    void appendCrc_structureIsCorrect() {
        String payload = "0002010102125811026304";  // arbitrary payload ending before tag 63
        // Strip any existing 6304 to get the base
        String base = "00020101021258110263";
        String full = Crc16.appendCrc(base);
        // Must end with 6304XXXX (8 chars)
        assertTrue(full.contains("6304"), "appendCrc must include 6304 prefix");
        assertEquals(base.length() + 8, full.length());
    }

    /** verify returns true for a valid payload. */
    @Test
    void verify_validPayload_returnsTrue() {
        String base = "00020101025812026304";
        String full = Crc16.appendCrc(base);
        assertTrue(Crc16.verify(full));
    }

    /** verify returns false when the CRC is corrupted. */
    @Test
    void verify_tampered_returnsFalse() {
        String base = "00020101025812026304";
        String full  = Crc16.appendCrc(base);
        // Flip last char
        String tampered = full.substring(0, full.length() - 1) + "X";
        assertFalse(Crc16.verify(tampered));
    }
}
