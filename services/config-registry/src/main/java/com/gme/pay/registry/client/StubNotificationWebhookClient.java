package com.gme.pay.registry.client;

import com.gme.pay.contracts.WebhookEndpointRegistrationCommand;
import com.gme.pay.contracts.WebhookEndpointRegistrationView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OPT-IN {@link NotificationWebhookClient}: an in-process stand-in so a unit
 * slice or a single-service local run never needs the notification-webhook
 * service running. Requires an explicit
 * {@code gmepay.notification-webhook.client=stub}.
 *
 * <h2>⚠ This bean issues a DEAD signing secret (gap T1-1)</h2>
 *
 * <p>The {@code whsec_} secret below is minted here and nowhere else: no
 * {@code webhook_endpoint} row exists at notification-webhook, so the
 * {@code X-Gme-Signature} a partner computes with it can never be reproduced by
 * the dispatcher. This class used to carry {@code matchIfMissing = true} while
 * {@link RestNotificationWebhookClient} needed an explicit selector that no
 * environment ever set — so activation always handed out an unusable secret.
 * Defaults are now inverted (REST wins when the selector is absent).
 *
 * <p>Mirrors the real endpoint's contract faithfully enough for the
 * activation flow to be exercised end-to-end: endpoint ids are stable per
 * ({@code partnerId}, {@code environment}) — a replay returns the SAME id
 * with {@code newlyRegistered=false} and NO new secret (the one-time-reveal
 * idempotency the WebhookProvisioningService tests pin) — and first-time
 * registrations mint a {@code whsec_}-prefixed random secret.
 */
@Component
@ConditionalOnProperty(name = "gmepay.notification-webhook.client", havingValue = "stub")
public class StubNotificationWebhookClient implements NotificationWebhookClient {

    private static final Logger log =
            LoggerFactory.getLogger(StubNotificationWebhookClient.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, String> endpointIdsByKey = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(1000);

    /** Loud startup banner — see the class Javadoc (gap T1-1). */
    public StubNotificationWebhookClient() {
        log.warn("gmepay.notification-webhook.client=stub — webhook signing secrets issued by this"
                + " instance are LOCAL RANDOMNESS and are unknown to notification-webhook, so no"
                + " partner can verify a delivery signature. Never use this selector in an"
                + " environment whose activation output reaches a real partner.");
    }

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
