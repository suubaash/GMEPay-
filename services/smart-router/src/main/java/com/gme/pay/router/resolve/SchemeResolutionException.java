package com.gme.pay.router.resolve;

/** Runtime exception carrying one {@link ResolutionError} branch outcome. */
public class SchemeResolutionException extends RuntimeException {

    private final ResolutionError error;

    public SchemeResolutionException(ResolutionError error, String message) {
        super(message);
        this.error = error;
    }

    public ResolutionError error() {
        return error;
    }
}
