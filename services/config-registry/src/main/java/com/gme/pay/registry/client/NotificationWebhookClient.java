package com.gme.pay.registry.client;

import com.gme.pay.contracts.WebhookEndpointRegistrationCommand;
import com.gme.pay.contracts.WebhookEndpointRegistrationView;

/**
 * Port to the notification-webhook service's partner-activation provisioning
 * endpoint ({@code POST /v1/webhooks/endpoints}) — Slice 8 Lane D.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link RestNotificationWebhookClient} — production HTTP transport and
 *       the DEFAULT: selected by {@code gmepay.notification-webhook.client=rest}
 *       and by the selector being absent;</li>
 *   <li>{@link StubNotificationWebhookClient} — deterministic in-process
 *       stand-in for unit slices / single-service local runs, opt-in via
 *       {@code gmepay.notification-webhook.client=stub}. It mints a signing
 *       secret notification-webhook has never seen, so activation output from a
 *       stub-wired instance is unusable (gap T1-1).</li>
 * </ul>
 */
public interface NotificationWebhookClient {

    /**
     * Registers (or idempotently re-resolves) one partner webhook endpoint
     * and has the callee mint the HMAC signing secret.
     *
     * @return endpoint id + ONE-TIME plaintext secret ({@code null} secret on
     *         the idempotent replay — see
     *         {@code WebhookEndpointRegistrationView.newlyRegistered()}).
     */
    WebhookEndpointRegistrationView registerEndpoint(WebhookEndpointRegistrationCommand command);
}
