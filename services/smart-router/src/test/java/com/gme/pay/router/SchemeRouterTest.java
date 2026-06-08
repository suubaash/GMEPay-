package com.gme.pay.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gme.pay.errors.ApiException;
import com.gme.pay.errors.ErrorCode;
import org.junit.jupiter.api.Test;

class SchemeRouterTest {

    private final SchemeRouter router = new SchemeRouter();

    @Test
    void koreaResolvesToZeroPay() {
        assertEquals("ZEROPAY", router.resolve("KR"));
        assertEquals("ZEROPAY", router.resolve("kr"));
    }

    @Test
    void unknownCountryThrowsNoSchemeForLocation() {
        ApiException ex = assertThrows(ApiException.class, () -> router.resolve("ZZ"));
        assertEquals(ErrorCode.NO_SCHEME_FOR_LOCATION, ex.errorCode());
    }

    @Test
    void nullCountryIsRejected() {
        assertThrows(ApiException.class, () -> router.resolve(null));
    }
}
