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

    // Well-formed EMVCo TLV with template tag 26 sub-tag 00 = mn.qpay (placeholder QPay
    // AID — real GUID pending a sample QR from SendMN) and tag 58 = MN.
    private static final String QPAY_QR =
            "00020101021126110007mn.qpay5802MN5907UB MART6304ABCD";

    @Test
    @DisplayName("EMVCo QPay QR → network mn.qpay, country MN, MPM (SendMN placeholder)")
    void classifiesQpay() {
        Classification c = QrSchemeClassifier.classify(QPAY_QR);
        assertEquals("mn.qpay", c.networkIdentifier());
        assertEquals("MN", c.country());
        assertEquals(PaymentMode.MPM, c.mode());
        assertTrue(c.isKnown());
    }

    @Test
    @DisplayName("EMVCo QPay AID (RID A000000843) → normalised to network qpay")
    void classifiesQpayEmvcoAid() {
        // The AID form real QPay MPM QRs carry (sim-sendmn's seeded GUID A000000843000101):
        // template 26 sub-tag 00 = the AID, tag 58 = MN. The raw AID resolves to no routing
        // candidate, so the classifier must normalise the RID family to "qpay".
        String qr = "00020101021126340016A00000084300010101101453767113"
                + "5204541153034965802MN5917NOMIN SUPERMARKET6304ABCD";
        Classification c = QrSchemeClassifier.classify(qr);
        assertEquals("qpay", c.networkIdentifier());
        assertEquals("MN", c.country());
        assertEquals(PaymentMode.MPM, c.mode());
        assertTrue(c.isKnown());
    }

    @Test
    @DisplayName("Non-conformant payload with a qpay marker → network qpay (fallback)")
    void classifiesQpayMarkerFallback() {
        Classification c = QrSchemeClassifier.classify("garbage-qpay-marker-payload");
        assertEquals("qpay", c.networkIdentifier());
        assertTrue(c.isKnown());
    }

    @Test
    @DisplayName("EMVCo tag58=MN with no recognised network → synthetic sendmn network")
    void classifiesMongoliaByCountry() {
        // Valid TLV shape, template 26 with no sub-tag 00 (unknown network), country MN.
        String qr = "00020126080104unkn5802MN6304ABCD";
        Classification c = QrSchemeClassifier.classify(qr);
        assertEquals("sendmn", c.networkIdentifier());
        assertEquals("MN", c.country());
        assertTrue(c.isKnown());
    }

    @Test
    @DisplayName("Unrecognised / blank payload → UNKNOWN, not known")
    void unknownPayload() {
        assertFalse(QrSchemeClassifier.classify(null).isKnown());
        assertFalse(QrSchemeClassifier.classify("").isKnown());
        assertFalse(QrSchemeClassifier.classify("random-garbage-no-tlv").isKnown());
    }
}
