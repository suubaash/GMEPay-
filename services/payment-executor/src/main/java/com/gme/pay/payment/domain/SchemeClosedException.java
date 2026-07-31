package com.gme.pay.payment.domain;

import com.gme.pay.contracts.SchemeAvailability;
import com.gme.pay.errors.ErrorCode;

/**
 * Raised when a NEW payment is aimed at a scheme that is OUTSIDE its operating window right now —
 * gap <b>T3-6</b>, the {@code SCHEME_CLOSED}-class error the router and the pay path lacked.
 *
 * <p><b>What "closed" means here.</b> A {@code scheme_operating_hours} row (V024) exists for this
 * scheme on the current scheme-LOCAL weekday and the current scheme-local wall-clock time falls
 * outside its open..close window. It never means "no row was found": a missing schedule is
 * {@code UNVERIFIED} and is deliberately permitted (see {@link SchemeOperatingHoursGate}).
 *
 * <p><b>What has NOT happened when this is thrown.</b> The gate runs at the very start of a new
 * authorization, so: no float reserved or deducted, no transaction row created, no scheme call, no
 * ledger posting, no event. Exactly the pre-side-effect position of T4-2's limit rejections.
 *
 * <p><b>Not applied to</b> confirm/capture of an already-authorized payment, cancels or refunds — the
 * property {@code OperationalGate} documents as deliberate and which this gap keeps: an in-flight
 * payment must be completable, and a refund must be possible, after the rail's window has closed.
 *
 * <p>Unlike the earlier stable-string workarounds in this package
 * ({@link OperationalGateException}, {@link SchemeOperationNotSupportedException}), this one carries a
 * REAL canonical {@link ErrorCode#SCHEME_CLOSED} — added to lib-errors as part of T3-6 so both entry
 * points and smart-router emit one identical code with one identical HTTP status (409,
 * {@code retryable=false}) instead of three near-synonyms.
 *
 * <p>Extends {@link PaymentException} so existing {@code catch (PaymentException)} call sites keep
 * treating it as a payment-layer refusal, but it is deliberately NOT a {@code SchemeDeclinedException}
 * (the scheme made no decision — it was never contacted) and NOT a {@code SchemeTimeoutException}
 * (there is no unknown outcome to reconcile, and an immediate retry cannot succeed).
 */
public class SchemeClosedException extends PaymentException {

    /** The canonical error code surfaced to callers. */
    public static final ErrorCode ERROR_CODE = ErrorCode.SCHEME_CLOSED;

    private final String schemeId;
    private final transient SchemeAvailability availability;

    public SchemeClosedException(SchemeAvailability availability) {
        super("scheme '" + availability.schemeId() + "' is not accepting traffic right now: "
                + availability.reason());
        this.schemeId = availability.schemeId();
        this.availability = availability;
    }

    /** The scheme that is closed (canonical roster code). */
    public String schemeId() {
        return schemeId;
    }

    /** The full evaluation behind the rejection — window, scheme-local time and zone. */
    public SchemeAvailability availability() {
        return availability;
    }

    /** The canonical error code ({@link ErrorCode#SCHEME_CLOSED}). */
    public ErrorCode code() {
        return ERROR_CODE;
    }
}
