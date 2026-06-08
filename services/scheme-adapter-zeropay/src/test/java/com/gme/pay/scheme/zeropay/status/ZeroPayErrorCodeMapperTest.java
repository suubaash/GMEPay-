package com.gme.pay.scheme.zeropay.status;

import com.gme.pay.errors.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain JUnit 5 unit tests for ZeroPay status code to canonical ErrorCode mapping.
 * No Spring context.
 */
class ZeroPayErrorCodeMapperTest {

    // -----------------------------------------------------------------------
    // Test 1: ZeroPayResultCode.of() resolves known codes
    // -----------------------------------------------------------------------

    @Test
    void resultCodeOf_knownCode_resolves() {
        assertEquals(ZeroPayResultCode.SUCCESS,                     ZeroPayResultCode.of("00"));
        assertEquals(ZeroPayResultCode.BATCH_SUCCESS,               ZeroPayResultCode.of("0000"));
        assertEquals(ZeroPayResultCode.MERCHANT_NOT_FOUND,          ZeroPayResultCode.of("1001"));
        assertEquals(ZeroPayResultCode.REGISTRATION_AMOUNT_MISMATCH,ZeroPayResultCode.of("9002"));
        assertEquals(ZeroPayResultCode.SYSTEM_UNAVAILABLE,          ZeroPayResultCode.of("5000"));
    }

    // -----------------------------------------------------------------------
    // Test 2: ZeroPayResultCode.of() throws for unknown code
    // -----------------------------------------------------------------------

    @Test
    void resultCodeOf_unknownCode_throwsException() {
        UnknownZeroPayResultCodeException ex = assertThrows(
                UnknownZeroPayResultCodeException.class,
                () -> ZeroPayResultCode.of("9999"));
        assertEquals("9999", ex.rawCode());
        assertTrue(ex.getMessage().contains("9999"));
    }

    // -----------------------------------------------------------------------
    // Test 3: isSuccess() is correct for success and failure codes
    // -----------------------------------------------------------------------

    @Test
    void resultCode_isSuccess_distinguishesSuccessFromFailure() {
        assertTrue(ZeroPayResultCode.SUCCESS.isSuccess());
        assertTrue(ZeroPayResultCode.BATCH_SUCCESS.isSuccess());

        assertFalse(ZeroPayResultCode.MERCHANT_NOT_FOUND.isSuccess());
        assertFalse(ZeroPayResultCode.SYSTEM_UNAVAILABLE.isSuccess());
        assertFalse(ZeroPayResultCode.REGISTRATION_AMOUNT_MISMATCH.isSuccess());
    }

    // -----------------------------------------------------------------------
    // Test 4: isRetryable() is correct
    // -----------------------------------------------------------------------

    @Test
    void resultCode_isRetryable_onlyForTransientCodes() {
        assertTrue(ZeroPayResultCode.SYSTEM_UNAVAILABLE.isRetryable());
        assertTrue(ZeroPayResultCode.TIMEOUT.isRetryable());
        assertTrue(ZeroPayResultCode.INTERNAL_ERROR.isRetryable());

        assertFalse(ZeroPayResultCode.MERCHANT_NOT_FOUND.isRetryable());
        assertFalse(ZeroPayResultCode.DUPLICATE_REQUEST.isRetryable());
        assertFalse(ZeroPayResultCode.REGISTRATION_AMOUNT_MISMATCH.isRetryable());
    }

    // -----------------------------------------------------------------------
    // Test 5: mapper maps merchant codes to MERCHANT_NOT_FOUND
    // -----------------------------------------------------------------------

    @Test
    void mapper_merchantNotFound_mapsToCanonicalMerchantNotFound() {
        assertEquals(ErrorCode.MERCHANT_NOT_FOUND,
                ZeroPayErrorCodeMapper.toErrorCode(ZeroPayResultCode.MERCHANT_NOT_FOUND));
        assertEquals(ErrorCode.MERCHANT_NOT_FOUND,
                ZeroPayErrorCodeMapper.toErrorCode(ZeroPayResultCode.MERCHANT_INACTIVE));
        assertEquals(ErrorCode.MERCHANT_NOT_FOUND,
                ZeroPayErrorCodeMapper.toErrorCode(ZeroPayResultCode.QR_DEACTIVATED));
    }

    // -----------------------------------------------------------------------
    // Test 6: mapper maps duplicate request to IDEMPOTENCY_CONFLICT
    // -----------------------------------------------------------------------

    @Test
    void mapper_duplicateRequest_mapsToIdempotencyConflict() {
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT,
                ZeroPayErrorCodeMapper.toErrorCode(ZeroPayResultCode.DUPLICATE_REQUEST));
    }

    // -----------------------------------------------------------------------
    // Test 7: mapper maps system errors to SCHEME_UNAVAILABLE
    // -----------------------------------------------------------------------

    @Test
    void mapper_systemUnavailable_mapsToSchemeUnavailable() {
        assertEquals(ErrorCode.SCHEME_UNAVAILABLE,
                ZeroPayErrorCodeMapper.toErrorCode(ZeroPayResultCode.SYSTEM_UNAVAILABLE));
        assertEquals(ErrorCode.SCHEME_UNAVAILABLE,
                ZeroPayErrorCodeMapper.toErrorCode(ZeroPayResultCode.TIMEOUT));
    }

    // -----------------------------------------------------------------------
    // Test 8: raw string overload works end-to-end
    // -----------------------------------------------------------------------

    @Test
    void mapper_rawStringOverload_resolvesThenMaps() {
        assertEquals(ErrorCode.IDEMPOTENCY_CONFLICT,
                ZeroPayErrorCodeMapper.toErrorCode("3001"));
        assertEquals(ErrorCode.MERCHANT_NOT_FOUND,
                ZeroPayErrorCodeMapper.toErrorCode("1001"));
        assertEquals(ErrorCode.SCHEME_UNAVAILABLE,
                ZeroPayErrorCodeMapper.toErrorCode("5000"));
    }

    // -----------------------------------------------------------------------
    // Test 9: raw string overload propagates UnknownZeroPayResultCodeException
    // -----------------------------------------------------------------------

    @Test
    void mapper_unknownRawCode_throwsUnknownCodeException() {
        assertThrows(UnknownZeroPayResultCodeException.class,
                () -> ZeroPayErrorCodeMapper.toErrorCode("8888"));
    }

    // -----------------------------------------------------------------------
    // Test 10: registration amount mismatch maps to VALIDATION_ERROR
    // -----------------------------------------------------------------------

    @Test
    void mapper_registrationAmountMismatch_mapsToValidationError() {
        assertEquals(ErrorCode.VALIDATION_ERROR,
                ZeroPayErrorCodeMapper.toErrorCode(ZeroPayResultCode.REGISTRATION_AMOUNT_MISMATCH));
    }
}
