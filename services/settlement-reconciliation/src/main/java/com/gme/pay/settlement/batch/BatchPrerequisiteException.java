package com.gme.pay.settlement.batch;

/**
 * The §8.2 settlement prerequisite failed: the ZP0061/ZP0063 settlement request for a business
 * date was attempted before that date's payment registration completed (ZP0011 transmitted +
 * ZP0012 result received). Thrown BEFORE any batch row is created, so nothing persists and the
 * next scheduled run retries cleanly once registration catches up.
 */
public class BatchPrerequisiteException extends RuntimeException {

    public BatchPrerequisiteException(String message) {
        super(message);
    }
}
