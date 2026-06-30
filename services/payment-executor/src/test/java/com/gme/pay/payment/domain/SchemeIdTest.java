package com.gme.pay.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for {@link SchemeId} code→numeric-id resolution (Wave-3 schemeId fix). */
class SchemeIdTest {

    @Test
    @DisplayName("resolves roster codes case-insensitively to stable 1-based ids")
    void resolvesRosterCodes() {
        assertEquals(1L, SchemeId.resolve("zeropay"));
        assertEquals(1L, SchemeId.resolve("ZEROPAY"));
        assertEquals(2L, SchemeId.resolve("BAKONG"));
        assertEquals(4L, SchemeId.resolve("NAPAS_247"));
        assertEquals(7L, SchemeId.resolve("QRIS"));
    }

    @Test
    @DisplayName("tolerates corridor adapter-code suffixes (zeropay_kr → ZEROPAY)")
    void toleratesSuffix() {
        assertEquals(1L, SchemeId.resolve("zeropay_kr"));
    }

    @Test
    @DisplayName("null / blank / unknown code resolves to UNSET (0)")
    void unknownIsUnset() {
        assertEquals(SchemeId.UNSET, SchemeId.resolve(null));
        assertEquals(SchemeId.UNSET, SchemeId.resolve(""));
        assertEquals(SchemeId.UNSET, SchemeId.resolve("not-a-scheme"));
    }
}
