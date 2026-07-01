package com.gme.pay.payment.domain;

/**
 * Raised by the {@code OperationalGate} at the START of a NEW payment authorization when the platform
 * is paused / in maintenance, or when the resolved partner / scheme / route is suspended (Operations
 * operational gate). Carries a STABLE canonical error code — one of {@link #SYSTEM_PAUSED},
 * {@link #PARTNER_SUSPENDED}, {@link #SCHEME_SUSPENDED}, {@link #ROUTE_SUSPENDED} — surfaced verbatim
 * on the wire via {@code ApiError(code, …)} (lib-errors is frozen; these are not yet {@code ErrorCode}
 * enum members — see the integration request in the CHANGELOG).
 *
 * <p>{@code retryable} mirrors the intended HTTP semantics: a pause / maintenance is a transient
 * 503-style condition the caller may retry once the platform resumes; an individual entity suspension
 * is likewise retryable once the entity is reinstated.
 */
public class OperationalGateException extends RuntimeException {

    /** Global master kill switch or maintenance mode: platform accepts nothing new (503-style). */
    public static final String SYSTEM_PAUSED = "SYSTEM_PAUSED";
    /** The resolved partner is individually suspended (503-style). */
    public static final String PARTNER_SUSPENDED = "PARTNER_SUSPENDED";
    /** The resolved scheme is individually suspended (503-style). */
    public static final String SCHEME_SUSPENDED = "SCHEME_SUSPENDED";
    /** The resolved route is individually suspended (503-style). */
    public static final String ROUTE_SUSPENDED = "ROUTE_SUSPENDED";

    private final String code;

    public OperationalGateException(String code, String message) {
        super(message);
        this.code = code;
    }

    /** The stable canonical error code (see the constants above). */
    public String code() {
        return code;
    }
}
