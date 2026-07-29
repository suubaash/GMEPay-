package com.gme.pay.bff.client;

import com.gme.pay.bff.web.dto.WebhookConfigView;

import java.util.List;

/**
 * Read-only view of a partner's own webhook subscriptions, owned by
 * {@code notification-webhook} (the {@code webhook_endpoint} registry).
 *
 * <h2>Why this interface exists (gap register T1-3)</h2>
 *
 * <p>The Partner Portal's {@code GET /v1/portal/{partnerId}/webhooks} used to be answered by two
 * rows hardcoded INLINE in {@code PartnerPortalController} — {@code partner.example.com/{code}/
 * webhook/payments} and {@code .../webhook/settlements}, both {@code ACTIVE}, with
 * {@code Instant.parse("2026-06-09T11:00:00Z")} as a literal "last delivered" time. There was no
 * client of any kind, so the page could not show a partner their actual endpoints, and it showed
 * two that do not exist under a domain nobody owns.
 *
 * <p>This is deliberately a separate seam from {@link WebhookOpsClient}, which is the OPERATOR view
 * of the same service (delivery backlog + replay). Both talk to notification-webhook; they answer
 * different questions for different audiences.
 *
 * <p>Read-only by design: webhook URL / event-type / secret-rotation writes are the Phase-2
 * self-serve surface still gated behind the open product decision (gap T1-5), and the Portal keeps
 * its "coming in Phase 2" affordance.
 */
public interface PortalWebhookClient {

    /**
     * The webhook endpoints registered for {@code partnerCode}, or an EMPTY list (never null) when
     * the partner has none, cannot be resolved, or notification-webhook is unreachable.
     *
     * <p>Never throws and never fabricates: an unavailable upstream yields no rows, so the Portal
     * shows its "No webhooks configured" empty state rather than invented endpoints.
     */
    List<WebhookConfigView> listForPartner(String partnerCode);
}
