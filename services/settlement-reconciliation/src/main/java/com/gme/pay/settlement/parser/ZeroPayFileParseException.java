package com.gme.pay.settlement.parser;

/**
 * Thrown when a ZeroPay inbound result file cannot be parsed — format error, truncated file,
 * unrecognised file type, or checksum mismatch.
 */
public class ZeroPayFileParseException extends RuntimeException {

    public ZeroPayFileParseException(String message) {
        super(message);
    }

    public ZeroPayFileParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
