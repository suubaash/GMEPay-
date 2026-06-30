package com.gme.pay.router.resolve;

/**
 * The branch outcomes of data-driven scheme-for-location resolution
 * (cross-service request from qr-service). Each is a distinct, named failure so
 * a caller can tell "you asked wrong" (validation), "this corridor has nothing"
 * (no scheme), and the two partial-match diagnostics (wrong presentment mode /
 * wrong direction) apart instead of getting one opaque 404.
 *
 * <h2>Why a router-local enum and not {@code lib-errors} {@code ErrorCode}</h2>
 *
 * <p>The canonical {@code ErrorCode} enum (FROZEN {@code lib-errors}) has
 * {@code VALIDATION_ERROR} and {@code NO_SCHEME_FOR_LOCATION} but NOT
 * {@code PAYMENT_MODE_NOT_SUPPORTED} / {@code DIRECTION_NOT_ENABLED}. Adding
 * them is an INTEGRATION REQUEST against a frozen lib; until that lands the
 * router owns its own resolution-error vocabulary here. The {@link #wireCode()}
 * strings are the contract surface qr-service binds to; {@link #httpStatus()}
 * mirrors the canonical mapping (400 / 404) so the eventual lib-errors promotion
 * is behaviour-preserving.
 */
public enum ResolutionError {

    /** Caller supplied a malformed / missing parameter. Maps to canonical VALIDATION_ERROR. */
    VALIDATION_ERROR(400, "VALIDATION_ERROR"),

    /**
     * Scheme rows exist for the location + direction but none is wired for the
     * requested presentment mode (CPM/MPM). Distinct from NO_SCHEME so the
     * wallet can prompt the customer to switch modes rather than fail hard.
     */
    PAYMENT_MODE_NOT_SUPPORTED(409, "PAYMENT_MODE_NOT_SUPPORTED"),

    /**
     * Scheme rows exist for the location but none is enabled for the requested
     * transaction direction (INBOUND/OUTBOUND/DOMESTIC). Distinct from NO_SCHEME
     * so the corridor's existence is still signalled.
     */
    DIRECTION_NOT_ENABLED(409, "DIRECTION_NOT_ENABLED"),

    /** No enabled scheme is wired for the location at all. Maps to canonical NO_SCHEME_FOR_LOCATION. */
    NO_SCHEME_FOR_LOCATION(404, "NO_SCHEME_FOR_LOCATION");

    private final int httpStatus;
    private final String wireCode;

    ResolutionError(int httpStatus, String wireCode) {
        this.httpStatus = httpStatus;
        this.wireCode = wireCode;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** Stable string code on the wire / in the {@code error.code} field. */
    public String wireCode() {
        return wireCode;
    }
}
