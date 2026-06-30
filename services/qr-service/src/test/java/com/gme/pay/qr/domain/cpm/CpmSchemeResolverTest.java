package com.gme.pay.qr.domain.cpm;

import com.gme.pay.qr.exception.QRErrorCode;
import com.gme.pay.qr.exception.QRParseException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Scheme resolution + NO_SCHEME_FOR_LOCATION handling (WBS 5.3-T04). */
class CpmSchemeResolverTest {

    private final CpmSchemeResolver resolver = new CpmSchemeResolver("KR");

    @Test
    void resolvesZeropayForSupportedCountry() {
        assertEquals("ZEROPAY", resolver.resolve("KR", null));
    }

    @Test
    void lowercaseCountryStillResolves() {
        assertEquals("ZEROPAY", resolver.resolve("kr", null));
    }

    @Test
    void unsupportedCountryThrowsNoScheme() {
        QRParseException ex = assertThrows(QRParseException.class, () -> resolver.resolve("US", null));
        assertEquals(QRErrorCode.NO_SCHEME_FOR_LOCATION, ex.getErrorCode());
    }

    @Test
    void unsupportedSchemeHintThrowsUnknownScheme() {
        QRParseException ex =
                assertThrows(QRParseException.class, () -> resolver.resolve("KR", "KHQR"));
        assertEquals(QRErrorCode.QR_UNKNOWN_SCHEME, ex.getErrorCode());
    }

    @Test
    void matchingSchemeHintAccepted() {
        assertEquals("ZEROPAY", resolver.resolve("KR", "zeropay"));
    }

    @Test
    void multiCountryConfigHonoured() {
        CpmSchemeResolver multi = new CpmSchemeResolver("KR, MN ,US");
        assertEquals("ZEROPAY", multi.resolve("MN", null));
        assertEquals("ZEROPAY", multi.resolve("US", null));
    }
}
