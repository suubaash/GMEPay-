package com.gme.pay.vault;

/**
 * Single unchecked failure type of the {@link VaultClient} port. Wraps the
 * backend-specific exception zoo (MinIO SDK checked exceptions, IO errors) so
 * callers handle one type and the port stays SDK-agnostic.
 */
public class VaultException extends RuntimeException {

    public VaultException(String message) {
        super(message);
    }

    public VaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
