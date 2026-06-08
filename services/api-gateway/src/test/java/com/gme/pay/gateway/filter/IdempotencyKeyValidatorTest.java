package com.gme.pay.gateway.filter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.gme.pay.gateway.filter.IdempotencyKeyValidator.ValidationResult.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plain JUnit 5 unit tests for {@link IdempotencyKeyValidator}.
 *
 * <p>Covers all boundary-length cases specified in API-05 §2.6 and ticket 8.1-T08.
 * No Spring context, no Docker, no running server.
 */
class IdempotencyKeyValidatorTest {

    // -----------------------------------------------------------------------
    // Null / missing header
    // -----------------------------------------------------------------------

    @Test
    void nullKey_returnsMissing() {
        assertEquals(MISSING, IdempotencyKeyValidator.validate(null));
    }

    // -----------------------------------------------------------------------
    // Below minimum length (< 16)
    // -----------------------------------------------------------------------

    @Test
    void emptyKey_returnsInvalidLength() {
        assertEquals(INVALID_LENGTH, IdempotencyKeyValidator.validate(""));
    }

    @Test
    void fifteenCharKey_returnsInvalidLength() {
        // 15 chars — one below the minimum
        assertEquals(INVALID_LENGTH, IdempotencyKeyValidator.validate("a".repeat(15)));
    }

    // -----------------------------------------------------------------------
    // At exactly minimum boundary (== 16)
    // -----------------------------------------------------------------------

    @Test
    void sixteenCharKey_returnsValid() {
        assertEquals(VALID, IdempotencyKeyValidator.validate("a".repeat(16)));
    }

    // -----------------------------------------------------------------------
    // Common valid values
    // -----------------------------------------------------------------------

    @Test
    void uuidKey_returnsValid() {
        // Standard UUID is 36 chars — well within [16, 128]
        assertEquals(VALID, IdempotencyKeyValidator.validate("550e8400-e29b-41d4-a716-446655440000"));
    }

    // -----------------------------------------------------------------------
    // At exactly maximum boundary (== 128)
    // -----------------------------------------------------------------------

    @Test
    void oneHundredTwentyEightCharKey_returnsValid() {
        assertEquals(VALID, IdempotencyKeyValidator.validate("x".repeat(128)));
    }

    // -----------------------------------------------------------------------
    // Above maximum length (> 128)
    // -----------------------------------------------------------------------

    @Test
    void oneHundredTwentyNineCharKey_returnsInvalidLength() {
        assertEquals(INVALID_LENGTH, IdempotencyKeyValidator.validate("x".repeat(129)));
    }

    @Test
    void veryLongKey_returnsInvalidLength() {
        assertEquals(INVALID_LENGTH, IdempotencyKeyValidator.validate("z".repeat(256)));
    }

    // -----------------------------------------------------------------------
    // Parameterized: all lengths 1-15 must be INVALID_LENGTH
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {1, 5, 10, 14, 15})
    void keyShorterThanMin_returnsInvalidLength(int length) {
        assertEquals(INVALID_LENGTH, IdempotencyKeyValidator.validate("a".repeat(length)));
    }

    // -----------------------------------------------------------------------
    // Parameterized: lengths 16-128 must be VALID
    // -----------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(ints = {16, 17, 32, 64, 127, 128})
    void keyWithinBounds_returnsValid(int length) {
        assertEquals(VALID, IdempotencyKeyValidator.validate("a".repeat(length)));
    }
}
