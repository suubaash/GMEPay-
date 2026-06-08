package com.gme.pay.notify.domain;

/**
 * Port (interface) for making outbound HTTP POST calls to partner webhook endpoints.
 *
 * <p>Production implementation uses Spring WebClient; test implementations use mocks or
 * WireMock stubs — the domain stays framework-free.
 */
public interface WebhookHttpClient {

    /**
     * Performs an HTTP POST of the given {@link WebhookSender.WebhookRequest}.
     *
     * @param request the fully-prepared request (URL, body, signed headers)
     * @return delivery result including HTTP status and response body
     */
    WebhookSender.WebhookDeliveryResult post(WebhookSender.WebhookRequest request);
}
