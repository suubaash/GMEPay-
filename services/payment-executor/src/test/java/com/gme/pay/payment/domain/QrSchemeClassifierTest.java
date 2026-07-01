package com.gme.pay.payment.domain;

import com.gme.pay.payment.domain.QrSchemeClassifier.Classification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for {@link QrSchemeClassifier} (ADR-016 §1). */
class QrSchemeClassifierTest {

    // Well-formed EMVCo TLV: template tag 26 sub-tag 00 = network id; tag 58 = country.
    private static final String FONEPAY_QR =
            "00020101021126150011fonepay.com5802NP5910KINAUN PVT6304ABCD";
    private static final String ZEROPAY_QR =
            "00020101021126260011com.zeropay010888888885802KR5910COFFEE HUT6304ABCD";

    @Test
    @DisplayName("EMVCo Fonepay QR → network fonepay.com, country NP, MPM")
    void classifiesFonepay() {
        Classification c = QrSchemeClassifier.classify(FONEPAY_QR);
        assertEquals("fonepay.com", c.networkIdentifier());
        assertEquals("NP", c.country());
        assertEquals(PaymentMode.MPM, c.mode());
        assertTrue(c.isKnown());
    }

    @Test
    @DisplayName("EMVCo ZeroPay QR → network com.zeropay, country KR")
    void classifiesZeroPay() {
        Classification c = QrSchemeClassifier.classify(ZEROPAY_QR);
        assertEquals("com.zeropay", c.networkIdentifier());
        assertEquals("KR", c.country());
    }

    @Test
    @DisplayName("JSON Khalti QR → network khalti (shape-classified)")
    void classifiesKhaltiJson() {
        String qr = "{\"scheme\":\"khalti\",\"merchant\":\"M123\",\"amount\":1000}";
        Classification c = QrSchemeClassifier.classify(qr);
        assertEquals("khalti", c.networkIdentifier());
        assertTrue(c.isKnown());
    }

    @Test
    @DisplayName("EMVCo country tag 58 is parsed (KR)")
    void parsesCountryTag() {
        Classification c = QrSchemeClassifier.classify(ZEROPAY_QR);
        assertEquals("KR", c.country());
    }

    @Test
    @DisplayName("Unrecognised / blank payload → UNKNOWN, not known")
    void unknownPayload() {
        assertFalse(QrSchemeClassifier.classify(null).isKnown());
        assertFalse(QrSchemeClassifier.classify("").isKnown());
        assertFalse(QrSchemeClassifier.classify("random-garbage-no-tlv").isKnown());
    }
}
