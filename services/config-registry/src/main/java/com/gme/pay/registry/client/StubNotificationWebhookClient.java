package com.gme.pay.registry.client;

import com.gme.pay.contracts.WebhookEndpointRegistrationCommand;
import com.gme.pay.contracts.WebhookEndpointRegistrationView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default {@link NotificationWebhookClient}: an in-process stand-in so local
 * dev and test contexts never need the notification-webhook service running.
 * Active unless {@code gmepay.notification-webhook.client=rest} promotes
 * {@link RestNotificationWebhookClient} — the same stub-by-default discipline
 * as {@link com.gme.pay.registry.kyb.StubKybClient}.
 *
 * <p>Mirrors the real endpoint's contract faithfully enough for the
 * activation flow to be exercised end-to-end: endpoint ids are stable per
 * ({@code partnerId}, {@code environment}) — a replay returns the SAME id
 * with {@code newlyRegistered=false} and NO new secret (the one-time-reveal
 * idempotency the WebhookProvisioningService tests pin) — and first-time
 * registrations mint a {@code whsec_}-prefixed random secret.
 */
@Component
@ConditionalOnProperty(name = "gmepay.notification-webhook.client",
        havingValue = "stub", matchIfMissing = true)
public class StubNotificationWebhookClient implements NotificationWebhookClient {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, String> endpointIdsByKey = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1000);

    @Override
    public WebhookEndpointRegistrationView registerEndpoint(
            WebhookEndpointRegistrationCommand command) {
        String key = command.partnerId() + ":" + command.environment();
        String existing = endpointIdsByKey.get(key);
        if (existing != null) {
            return new WebhookEndpointRegistrationView(existing, null, false);
        }
        String endpointId = String.valueOf(sequence.incrementAndGet());
        endpointIdsByKey.put(key, endpointId);
        byte[] material = new byte[32];
        RANDOM.nextBytes(material);
        String secret = "whsec_"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        return new WebhookEndpointRegistrationView(endpointId, secret, true);
    }
}
