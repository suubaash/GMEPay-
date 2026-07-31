package com.gme.pay.notify.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the partner a webhook payload belongs to.
 *
 * <p>One copy of this parse, deliberately. It previously existed three times — in
 * {@code DefaultWebhookTargetResolver} (to find the endpoint), in {@code WebhookAlertService} (to
 * attribute a DLQ alert) and implicitly nowhere at enqueue time — and per-endpoint fairness makes the
 * partner id load-bearing for <em>selection</em> as well. Three copies of the rule that decides which
 * partner a delivery belongs to is how one of them ends up disagreeing, and a disagreement here means
 * a row selected under one partner's fair share and delivered to another's endpoint.
 *
 * <p>Accepts both shapes the platform emits: a flat top-level {@code partnerId} (a direct
 * {@code KafkaEventPublisher} publish) and the canonical outbox envelope, where the event's own fields
 * are nested under {@code payload}
 * ({@code {eventType,aggregateId,occurredAt,payload:{partnerId}}}).
 */
public final class WebhookPayloads {

    private static final Logger log = LoggerFactory.getLogger(WebhookPayloads.class);

    /** Stateless and thread-safe once configured; the parse happens on every drain worker. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WebhookPayloads() {
    }

    /**
     * @return the numeric {@code partnerId} carried by the payload, or {@code null} when it is
     *         absent, non-numeric or the payload does not parse. {@code null} is a legitimate answer
     *         and every caller must handle it — an unattributable delivery is still a delivery.
     */
    public static Long partnerId(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode root = MAPPER.readTree(payload);
            JsonNode node = root.get("partnerId");
            if (node == null || node.isNull()) {
                node = root.path("payload").get("partnerId"); // outbox envelope nests event fields
            }
            if (node == null || node.isNull()) {
                return null;
            }
            if (node.isNumber()) {
                return node.asLong();
            }
            String text = node.asText();
            return (text == null || text.isBlank()) ? null : Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        } catch (Exception e) {
            log.debug("could not parse webhook payload for partnerId: {}", e.getMessage());
            return null;
        }
    }
}
