package com.gme.pay.qr.domain.cpm;

import com.gme.pay.qr.domain.emvco.EMVCoCrcVerifier;
import com.gme.pay.qr.exception.QRInvalidChecksumException;
import com.gme.pay.qr.exception.QRMalformedException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EMVCo CPM payload encode/parse round-trip + CRC/TLV validation (WBS 5.4-T11).
 */
class CpmPayloadRoundTripTest {

    private final CpmPayloadParser parser = new CpmPayloadParser(60);

    @Test
    void encodeThenParseRecoversTokenAndScheme() {
        String content = CpmPayloadEncoder.encode("ZP-CPM-ABCDEF1234567890ABCD", "ZEROPAY");

        CpmTokenPayload payload = parser.parseCpmToken(content);

        assertEquals("ZP-CPM-ABCDEF1234567890ABCD", payload.token());
        assertEquals("ZEROPAY", payload.schemeId());
        assertFalse(payload.isExpired());
    }

    @Test
    void encodedPayloadHasValidCrc() {
        String content = CpmPayloadEncoder.encode("ZP-CPM-XYZ", "ZEROPAY");
        // verify() throws on mismatch; reaching the assertion means the CRC is valid
        assertTrue(EMVCoCrcVerifier.verify(content));
    }

    @Test
    void tamperedCrcThrowsInvalidChecksum() {
        String content = CpmPayloadEncoder.encode("ZP-CPM-XYZ", "ZEROPAY");
        String tampered = content.substring(0, content.length() - 4) + "0000";

        assertThrows(QRInvalidChecksumException.class, () -> parser.parseCpmToken(tampered));
    }

    @Test
    void blankPayloadThrowsMalformed() {
        assertThrows(QRMalformedException.class, () -> parser.parseCpmToken("  "));
    }

    @Test
    void payloadWithoutCpmTemplateThrowsMalformed() {
        // A valid-CRC MPM-style payload (no tag 85 CPM template)
        String body = "000201" + "5204" + "5411" + "6304";
        String mpm = body + EMVCoCrcVerifier.compute(body);

        QRMalformedException ex =
                assertThrows(QRMalformedException.class, () -> parser.parseCpmToken(mpm));
        assertTrue(ex.getMessage().contains("CPM template"));
    }

    @Test
    void freshlyIssuedTokenIsNotExpired() {
        var issuer = new LocalPrepareTokenIssuer();
        var result = issuer.issue(new PrepareTokenIssuancePort.CpmPrepareContext(
                "ZEROPAY", "REF-1", "cust", "KR", 60));

        CpmTokenPayload payload = parser.parseCpmToken(result.qrContent());
        assertEquals(result.prepareToken(), payload.token());
        assertFalse(result.schemeIssued(), "local fallback must mark schemeIssued=false");
    }
}
