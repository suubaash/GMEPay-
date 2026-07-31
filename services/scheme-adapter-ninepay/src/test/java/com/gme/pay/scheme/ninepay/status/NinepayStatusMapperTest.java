package com.gme.pay.scheme.ninepay.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Mapping tables for {@link NinepayStatusMapper} — 9Pay statuses and IPN codes 000–009. */
class NinepayStatusMapperTest {

    @ParameterizedTest
    @CsvSource({
            "PENDING, PENDING",
            "PROCESSING, PROCESSING",
            "SUCCESS, SUCCESS",
            "FAIL, FAILED",
            "success, SUCCESS",     // case-insensitive
            "' SUCCESS ', SUCCESS", // trimmed
    })
    @DisplayName("scheme status field maps to the canonical PayoutStatus")
    void schemeStatus_maps(String wire, PayoutStatus expected) {
        assertEquals(expected, NinepayStatusMapper.fromSchemeStatus(wire));
    }

    @Test
    @DisplayName("unrecognised/null scheme status maps to UNKNOWN — never auto-fail")
    void schemeStatus_unknownNeverFails() {
        assertEquals(PayoutStatus.UNKNOWN, NinepayStatusMapper.fromSchemeStatus("WEIRD_NEW_STATE"));
        assertEquals(PayoutStatus.UNKNOWN, NinepayStatusMapper.fromSchemeStatus(null));
    }

    @ParameterizedTest
    @CsvSource({
            "000, SUCCESS",   // successful transaction
            "004, PENDING",   // not yet processed by bank — retryable, NOT final
            "008, HELD",      // held by 9Pay pending merchant confirmation
            "009, REVERSED",  // bank reversal AFTER success
            "001, FAILED", "002, FAILED", "003, FAILED",
            "005, FAILED", "006, FAILED", "007, FAILED",
    })
    @DisplayName("IPN message codes 000-009 map per section 6 of the spec")
    void ipnCode_maps(String code, PayoutStatus expected) {
        assertEquals(expected, NinepayStatusMapper.fromIpn(code, "SUCCESS"));
    }

    @Test
    @DisplayName("IPN code wins over the status field (009 reversal arrives with status SUCCESS)")
    void ipnCode_winsOverStatus() {
        assertEquals(PayoutStatus.REVERSED, NinepayStatusMapper.fromIpn("009", "SUCCESS"));
        assertEquals(PayoutStatus.HELD, NinepayStatusMapper.fromIpn("008", "PROCESSING"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " "})
    @DisplayName("absent IPN code falls back to the status field (code is optional + unsigned)")
    void ipnCode_absentFallsBackToStatus(String blank) {
        assertEquals(PayoutStatus.FAILED, NinepayStatusMapper.fromIpn(blank, "FAIL"));
        assertEquals(PayoutStatus.SUCCESS, NinepayStatusMapper.fromIpn(null, "SUCCESS"));
    }

    @Test
    @DisplayName("finality: SUCCESS/FAILED/REVERSED are final; HELD/UNKNOWN/PENDING are not")
    void finality() {
        assertTrue(PayoutStatus.SUCCESS.isFinal());
        assertTrue(PayoutStatus.FAILED.isFinal());
        assertTrue(PayoutStatus.REVERSED.isFinal());
        assertFalse(PayoutStatus.HELD.isFinal());
        assertFalse(PayoutStatus.UNKNOWN.isFinal());
        assertFalse(PayoutStatus.PENDING.isFinal());
        assertFalse(PayoutStatus.PROCESSING.isFinal());
        assertFalse(PayoutStatus.SUBMITTED.isFinal());
    }
}
