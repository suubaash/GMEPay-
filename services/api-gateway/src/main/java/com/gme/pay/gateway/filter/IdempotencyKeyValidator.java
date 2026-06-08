package com.gme.pay.gateway.filter;

/**
 * Pure-domain helper that validates the {@code Idempotency-Key} header value per API-05
 * section 2.6.
 *
 * <p>Rules:
 * <ul>
 *   <li>Key must be present on all POST requests.</li>
 *   <li>Length must be 16–128 characters (inclusive).</li>
 * </ul>
 *
 * <p>This class has no Spring or Reactor dependency so it can be unit-tested without a container.
 */
public final class IdempotencyKeyValidator {

    /** Minimum allowed idempotency key length (inclusive). */
    public static final int MIN_LENGTH = 16;
    /** Maximum allowed idempotency key length (inclusive). */
    public static final int MAX_LENGTH = 128;

    private IdempotencyKeyValidator() {}

    /**
     * Result of validating an idempotency key value.
     */
    public enum ValidationResult {
        /** Key is absent — return 400 MISSING_IDEMPOTENCY_KEY. */
        MISSING,
        /** Key is present but length is outside [16, 128] — return 400 VALIDATION_ERROR. */
        INVALID_LENGTH,
        /** Key is present and length is valid — allow request to continue. */
        VALID
    }

    /**
     * Validate the idempotency key string.
     *
     * @param key the raw header value, or {@code null} if the header was absent
     * @return the {@link ValidationResult} describing the outcome
     */
    public static ValidationResult validate(String key) {
        if (key == null) {
            return ValidationResult.MISSING;
        }
        int len = key.length();
        if (len < MIN_LENGTH || len > MAX_LENGTH) {
            return ValidationResult.INVALID_LENGTH;
        }
        return ValidationResult.VALID;
    }
}
