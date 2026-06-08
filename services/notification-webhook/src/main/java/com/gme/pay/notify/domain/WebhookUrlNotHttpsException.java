package com.gme.pay.notify.domain;

/**
 * Thrown when a webhook target URL does not use the HTTPS scheme.
 * This check happens before any network call is made (defense-in-depth).
 */
public class WebhookUrlNotHttpsException extends RuntimeException {

    public WebhookUrlNotHttpsException(String message) {
        super(message);
    }
}
