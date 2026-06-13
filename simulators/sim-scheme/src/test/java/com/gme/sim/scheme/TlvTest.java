package com.gme.sim.scheme;

import com.gme.sim.scheme.emvco.EmvcoQrEncoder;
import com.gme.sim.scheme.emvco.TlvField;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TLV round-trip tests.
 */
class TlvTest {

    @Test
    void encode_formatsTagLengthValue() {
        TlvField f = new TlvField(0, "01");
        assertEquals("000201", f.encode(), "Tag 00, length 02, value 01");
    }

    @Test
    void encode_padsBothFieldsToTwoDigits() {
        TlvField f = new TlvField(1, "12");
        assertEquals("010212", f.encode());
    }

    @Test
    void extractTagValue_roundTrip() {
        // Build a mini TLV string and extract it back
        String tlv = new TlvField(0, "01").encode()
                   + new TlvField(1, "DYNAMIC").encode()
                   + new TlvField(52, "5812").encode();

        assertEquals("01",      EmvcoQrEncoder.extractTagValue(tlv, 0));
        assertEquals("DYNAMIC", EmvcoQrEncoder.extractTagValue(tlv, 1));
        assertEquals("5812",    EmvcoQrEncoder.extractTagValue(tlv, 52));
        assertNull(EmvcoQrEncoder.extractTagValue(tlv, 99), "absent tag returns null");
    }

    @Test
    void extractTagValue_emptyTlv_returnsNull() {
        assertNull(EmvcoQrEncoder.extractTagValue("", 0));
    }
}
