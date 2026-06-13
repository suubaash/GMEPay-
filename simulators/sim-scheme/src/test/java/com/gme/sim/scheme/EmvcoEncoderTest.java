package com.gme.sim.scheme;

import com.gme.sim.scheme.config.SchemeProfile;
import com.gme.sim.scheme.emvco.Crc16;
import com.gme.sim.scheme.emvco.EmvcoQrEncoder;
import com.gme.sim.scheme.model.MerchantRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EMVCo encoder: static/dynamic tag 01 values + merchant-id round-trip.
 */
class EmvcoEncoderTest {

    private static final MerchantRecord MERCHANT = new MerchantRecord(
            "KHQR-M001", "Angkor Coffee", "Siem Reap", "5812");

    @Test
    void staticQr_hasTag01_value11() {
        String payload = EmvcoQrEncoder.buildStatic(MERCHANT, SchemeProfile.KHQR);
        // Tag 01, length 02, value "11"
        assertTrue(payload.contains("010211"),
                "Static QR must contain tag 01 = 11");
    }

    @Test
    void dynamicQr_hasTag01_value12() {
        String payload = EmvcoQrEncoder.buildDynamic(
                MERCHANT, SchemeProfile.KHQR, new BigDecimal("25000.00"));
        assertTrue(payload.contains("010212"),
                "Dynamic QR must contain tag 01 = 12");
    }

    @Test
    void dynamicQr_hasTag54_withAmount() {
        BigDecimal amount = new BigDecimal("12500");
        String payload = EmvcoQrEncoder.buildDynamic(MERCHANT, SchemeProfile.KHQR, amount);
        BigDecimal extracted = EmvcoQrEncoder.extractAmount(payload);
        assertNotNull(extracted);
        assertEquals(0, extracted.compareTo(amount));
    }

    @Test
    void staticQr_noTag54() {
        String payload = EmvcoQrEncoder.buildStatic(MERCHANT, SchemeProfile.KHQR);
        assertNull(EmvcoQrEncoder.extractAmount(payload),
                "Static QR must not have tag 54");
    }

    @Test
    void merchantId_extractedCorrectly() {
        String payload = EmvcoQrEncoder.buildStatic(MERCHANT, SchemeProfile.KHQR);
        String extracted = EmvcoQrEncoder.extractMerchantId(payload, SchemeProfile.KHQR);
        assertEquals("KHQR-M001", extracted);
    }

    @Test
    void generatedPayload_hasCrc_thatVerifies() {
        String payload = EmvcoQrEncoder.buildDynamic(
                MERCHANT, SchemeProfile.KHQR, new BigDecimal("5000"));
        assertTrue(Crc16.verify(payload), "Generated payload CRC must verify");
    }
}
