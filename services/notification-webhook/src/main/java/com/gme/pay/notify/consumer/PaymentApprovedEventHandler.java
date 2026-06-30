package com.gme.pay.notify.consumer;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.contracts.events.PaymentApprovedPayload;
import com.gme.pay.notify.persistence.WebhookPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Handles a {@code payment.approved} event consumed from Kafka (17.4-G04):
 * deserializes the canonical {@link PaymentApprovedPayload} (lib-api-contracts,
 * camelCase, emitted by payment-executor on {@code gmepay.payment.approved}) and
 * enqueues a {@code PENDING} delivery row in {@code webhook_delivery_log} for the
 * dispatch pipeline ({@code WebhookSender} + {@code RetryPolicy}) to pick up. No
 * outbound HTTP happens here.
 *
 * <p>The delivery payload persisted to the log (and ultimately POSTed to the
 * partner) is a normalized re-serialization of the typed payload, so a partner
 * always receives the relevant approved-payment fields ({@code txnRef},
 * {@code partnerId}, {@code schemeId}, the margin/service-charge amounts) in the
 * canonical shape regardless of incidental wire differences on the topic.
 *
 * <p>Poison messages &mdash; unparseable JSON, wrong/missing {@code eventType},
 * missing transaction reference &mdash; raise {@link IllegalArgumentException}; the
 * Kafka error handler retries N times and then forwards the record to the
 * {@code gmepay.payment.approved.DLT} topic.
 *
 * <p>Idempotent under Kafka at-least-once redelivery: a duplicate event (same
 * transaction reference + event type) is skipped without error so the consumer can ack.
 */
@Service
public class PaymentApprovedEventHandler {

    /** Event type this handler accepts; anything else on the topic is poison. */
    public static final String EVENT_TYPE = PaymentApprovedPayload.EVENT_TYPE;

    /** Must fit {@code webhook_delivery_log.webhook_id VARCHAR(64)}. */
    static final int MAX_WEBHOOK_ID_LENGTH = 64;

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovedEventHandler.class);

    private final WebhookPersistenceService persistenceService;

    /** Reads camelCase + ISO-8601 {@code Instant}/{@code LocalDate} (jsr310). */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            // Defensive: tolerate additive producer fields not (yet) on the canonical record.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public PaymentApprovedEventHandler(WebhookPersistenceService persistenceService) {
        this.persistenceService = Objects.requireNonNull(persistenceService);
    }

    /**
     * Validates and enqueues one consumed record.
     *
     * @param recordKey the Kafka record key (the publisher sets it to the aggregate id);
     *                  used as a fallback when the payload lacks {@code txnRef}/{@code aggregateId}
     * @param payload   the raw JSON record value
     * @return {@code true} if a new delivery row was enqueued, {@code false} if the
     *         event was already enqueued (idempotent skip)
     * @throws IllegalArgumentException if the record is poison (invalid JSON / contract)
     */
    public boolean handle(String recordKey, String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payment.approved record has an empty payload");
        }

        PaymentApprovedPayload event;
        try {
            event = objectMapper.readValue(payload, PaymentApprovedPayload.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "payment.approved payload is not a valid PaymentApprovedPayload", e);
        }
        if (event == null) {
            throw new IllegalArgumentException("payment.approved payload deserialized to null");
        }

        if (!EVENT_TYPE.equals(event.eventType())) {
            throw new IllegalArgumentException(
                    "unexpected eventType on payment.approved topic: " + event.eventType());
        }

        // Transaction reference: prefer txnRef, then aggregateId, then the record key.
        String webhookId = firstNonBlank(event.txnRef(), event.aggregateId(), recordKey);
        if (webhookId == null) {
            throw new IllegalArgumentException(
                    "payment.approved event has no transaction reference (txnRef/aggregateId/record key)");
        }
        if (webhookId.length() > MAX_WEBHOOK_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "transaction reference exceeds " + MAX_WEBHOOK_ID_LENGTH + " chars: " + webhookId);
        }

        // Normalize the partner-facing delivery payload from the typed event so the
        // relevant approved-payment fields (txnRef, partnerId, schemeId, amounts) are
        // always carried in the canonical camelCase shape.
        String deliveryPayload = toDeliveryPayload(event);

        boolean enqueued = persistenceService
                .enqueuePendingIfAbsent(webhookId, EVENT_TYPE, deliveryPayload)
                .isPresent();
        if (enqueued) {
            log.info("enqueued webhook delivery: eventType={} txnRef={} partnerId={} schemeId={}",
                    EVENT_TYPE, webhookId, event.partnerId(), event.schemeId());
        } else {
            log.info("duplicate payment.approved event skipped (already enqueued): txnRef={}", webhookId);
        }
        return enqueued;
    }

    /**
     * Serializes the canonical {@link PaymentApprovedPayload} back to the JSON the
     * partner receives. {@code @JsonInclude(ALWAYS)} on the record keeps every
     * approved-payment field (txnRef, partnerId, schemeId, margins, service charge,
     * feeSharePct) present even when null/zero, so partner integrations bind a stable shape.
     */
    private String toDeliveryPayload(PaymentApprovedPayload event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JacksonException e) {
            // Should never happen for a record we just deserialized; treat as poison.
            throw new IllegalArgumentException("failed to serialize delivery payload", e);
        }
    }

    private static String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c;
            }
        }
        return null;
    }
}
