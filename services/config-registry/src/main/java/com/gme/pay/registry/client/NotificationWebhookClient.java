package com.gme.pay.registry.client;

import com.gme.pay.contracts.WebhookEndpointRegistrationCommand;
import com.gme.pay.contracts.WebhookEndpointRegistrationView;

/**
 * Port to the notification-webhook service's partner-activation provisioning
 * endpoint ({@code POST /v1/webhooks/endpoints}) — Slice 8 Lane D.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link RestNotificationWebhookClient} — production HTTP transport,
 *       active when {@code gmepay.notification-webhook.client=rest};</li>
 *   <li>{@link StubNotificationWebhookClient} — deterministic in-process
 *       default so {@code @DataJpaTest} slices and local dev never need the
 *       notification-webhook service running (the {@code StubKybClient}
 *       discipline).</li>
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
