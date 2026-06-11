package com.gme.pay.notify.consumer;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.notify.persistence.WebhookPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Handles a {@code payment.approved} event consumed from Kafka (17.4-G04):
 * validates the JSON payload and enqueues a {@code PENDING} delivery row in
 * {@code webhook_delivery_log} for the dispatch pipeline ({@code WebhookSender}
 * + {@code RetryPolicy}) to pick up. No outbound HTTP happens here.
 *
 * <p>Poison messages &mdash; unparseable JSON, wrong/missing {@code eventType},
 * missing aggregate id &mdash; raise {@link IllegalArgumentException}; the Kafka
 * error handler retries N times and then forwards the record to the
 * {@code gmepay.payment.approved.DLT} topic.
 *
 * <p>Idempotent under Kafka at-least-once redelivery: a duplicate event (same
 * aggregate id + event type) is skipped without error so the consumer can ack.
 */
@Service
public class PaymentApprovedEventHandler {

    /** Event type this handler accepts; anything else on the topic is poison. */
    public static final String EVENT_TYPE = "payment.approved";

    /** Must fit {@code webhook_delivery_log.webhook_id VARCHAR(64)}. */
    static final int MAX_WEBHOOK_ID_LENGTH = 64;

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovedEventHandler.class);

    private final WebhookPersistenceService persistenceService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PaymentApprovedEventHandler(WebhookPersistenceService persistenceService) {
        this.persistenceService = Objects.requireNonNull(persistenceService);
    }

    /**
     * Validates and enqueues one consumed record.
     *
     * @param recordKey the Kafka record key (the publisher sets it to the aggregate id);
     *                  used as a fallback when the payload lacks {@code aggregateId}
     * @param payload   the raw JSON record value
     * @return {@code true} if a new delivery row was enqueued, {@code false} if the
     *         event was already enqueued (idempotent skip)
     * @throws IllegalArgumentException if the record is poison (invalid JSON / contract)
     */
    public boolean handle(String recordKey, String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payment.approved record has an empty payload");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("payment.approved payload is not valid JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("payment.approved payload must be a JSON object");
        }

        String eventType = textOrNull(root, "eventType");
        if (!EVENT_TYPE.equals(eventType)) {
            throw new IllegalArgumentException(
                    "unexpected eventType on payment.approved topic: " + eventType);
        }

        String aggregateId = textOrNull(root, "aggregateId");
        if (aggregateId == null || aggregateId.isBlank()) {
            aggregateId = recordKey;
        }
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException(
                    "payment.approved event has no aggregateId (payload field or record key)");
        }
        if (aggregateId.length() > MAX_WEBHOOK_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "aggregateId exceeds " + MAX_WEBHOOK_ID_LENGTH + " chars: " + aggregateId);
        }

        boolean enqueued = persistenceService
                .enqueuePendingIfAbsent(aggregateId, EVENT_TYPE, payload)
                .isPresent();
        if (enqueued) {
            log.info("enqueued webhook delivery: eventType={} aggregateId={}", EVENT_TYPE, aggregateId);
        } else {
            log.info("duplicate payment.approved event skipped (already enqueued): aggregateId={}",
                    aggregateId);
        }
        return enqueued;
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
