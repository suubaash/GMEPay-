package com.gme.pay.bff.client.stub;

import com.gme.pay.bff.client.PortalWebhookClient;
import com.gme.pay.bff.web.dto.WebhookConfigView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Standalone-boot fallback for {@link PortalWebhookClient}: reports NO webhook endpoints.
 *
 * <h2>Why there are no sample rows (gap register T1-3)</h2>
 *
 * <p>The behaviour this replaces was not even a stub client — it was two rows built inline in
 * {@code PartnerPortalController.webhooks()}, pointing at
 * {@code https://partner.example.com/{code}/webhook/payments} and {@code .../webhook/settlements},
 * both labelled {@code ACTIVE}, with a literal {@code Instant.parse("2026-06-09T11:00:00Z")} as the
 * last delivery. Every partner saw the same two endpoints under a domain nobody controls, and a
 * partner debugging "why am I not receiving webhooks" was reading a page that had never consulted
 * the service that delivers them.
 *
 * <p>An empty list is the honest answer in stub mode: the BFF is not connected to
 * notification-webhook, so it knows of no endpoints, and the Portal shows its "No webhooks
 * configured" empty state. Real data requires {@code gmepay.notification-webhook.client=rest},
 * which activates {@link com.gme.pay.bff.client.rest.RestPortalWebhookClient}; that selector is set
 * on every deploy target.
 */
@Component
public class StubPortalWebhookClient implements PortalWebhookClient {

    private static final Logger log = LoggerFactory.getLogger(StubPortalWebhookClient.class);

    @Override
    public List<WebhookConfigView> listForPartner(String partnerCode) {
        log.debug("webhook list requested for partner '{}' but the BFF is in stub mode — reporting "
                + "no endpoints. Set GMEPAY_NOTIFICATION_WEBHOOK_CLIENT=rest to read the real "
                + "registry.", partnerCode);
        return List.of();
    }
}
