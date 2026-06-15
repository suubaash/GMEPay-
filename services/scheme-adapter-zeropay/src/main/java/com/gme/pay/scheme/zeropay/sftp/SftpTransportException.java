package com.gme.pay.scheme.zeropay.sftp;

/**
 * Thrown by {@link SftpTransport} implementations when a file transfer fails.
 */
public class SftpTransportException extends RuntimeException {

    public SftpTransportException(String message) {
        super(message);
    }

    public SftpTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
