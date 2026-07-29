package com.gme.pay.notify.consumer;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.contracts.events.PaymentReversedPayload;
import com.gme.pay.notify.persistence.WebhookPersistenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Handles a {@code payment.reversed} event consumed from {@code gmepay.payment.reversed} and enqueues the
 * partner-facing webhook delivery for it (gap T2-6).
 *
 * <h2>Why this exists</h2>
 *
 * <p>This service's consumer package handled exactly one event type — {@code payment.approved}. So a partner
 * was told when a payment succeeded and <b>never told when it was reversed or refunded</b>: the money went
 * back, the transaction became REFUNDED / REVERSED, and the partner's own ledger kept showing a live payment
 * until somebody reconciled by hand. Refunds are the event a partner most needs pushed, because unlike an
 * approval it is not the result of a call they made.
 *
 * <p>It is the exact structural twin of {@link PaymentApprovedEventHandler} — same validation, same
 * idempotent enqueue, same poison rules — and it reuses the <b>whole existing delivery pipeline unchanged</b>:
 * {@code WebhookDispatcher} drains PENDING rows regardless of event type and
 * {@code DefaultWebhookTargetResolver} resolves the endpoint + its own HKDF-derived per-endpoint secret from
 * the {@code partnerId} in the payload. <b>No signing change of any kind</b>: the T5-4 model (per-endpoint
 * derived secret, verified against the stored digest, fail-closed) signs a refund exactly as it signs an
 * approval. This handler adds a producer of delivery rows and nothing else.
 *
 * <h2>Idempotency</h2>
 *
 * <p>Enqueue is keyed on {@code (webhookId, eventType)}, and the event type here is
 * {@code payment.reversed} — distinct from {@code payment.approved} — so the refund delivery does not collide
 * with the approval delivery for the same transaction, while a Kafka redelivery of the same reversal is
 * skipped. A transaction that is reversed after being refunded (or vice versa) shares one delivery row by
 * design: both are the same "this payment was backed out" fact on the same contract, and a partner must not
 * receive it twice.
 *
 * <h2>Poison handling</h2>
 *
 * <p>Unparseable JSON, a wrong/missing {@code eventType} or a missing transaction reference raise
 * {@link IllegalArgumentException}; the Kafka error handler retries then dead-letters to
 * {@code gmepay.payment.reversed.DLT}, exactly as for approvals.
 */
@Service
public class PaymentReversedEventHandler {

    /** Event type this handler accepts; anything else on the topic is poison. */
    public static final String EVENT_TYPE = PaymentReversedPayload.EVENT_TYPE;

    private static final Logger log = LoggerFactory.getLogger(PaymentReversedEventHandler.class);

    private final WebhookPersistenceService persistenceService;

    /** Reads camelCase + ISO-8601 temporals, tolerant of additive producer fields. */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public PaymentReversedEventHandler(WebhookPersistenceService persistenceService) {
        this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService required");
    }

    /**
     * Validates and enqueues one consumed record.
     *
     * @param recordKey the Kafka record key (the publisher sets it to the aggregate id, i.e. the txnRef);
     *                  used as the transaction-reference fallback when the payload omits one
     * @param payload   the raw JSON record value (a serialised {@link PaymentReversedPayload})
     * @return {@code true} if a new delivery row was enqueued, {@code false} on an idempotent skip
     * @throws IllegalArgumentException if the record is poison (invalid JSON / contract)
     */
    public boolean handle(String recordKey, String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payment.reversed record has an empty payload");
        }

        PaymentReversedPayload event;
        try {
            event = objectMapper.readValue(payload, PaymentReversedPayload.class);
        } catch (JacksonException e) {
            throw new IllegalArgumentException(
                    "payment.reversed payload is not a valid PaymentReversedPayload", e);
        }
        if (event == null) {
            throw new IllegalArgumentException("payment.reversed payload deserialized to null");
        }

        if (!EVENT_TYPE.equals(event.eventType())) {
            throw new IllegalArgumentException(
                    "unexpected eventType on payment.reversed topic: " + event.eventType());
        }

        String webhookId = firstNonBlank(event.txnRef(), recordKey);
        if (webhookId == null) {
            throw new IllegalArgumentException(
                    "payment.reversed event has no transaction reference (txnRef / record key)");
        }
        if (webhookId.length() > PaymentApprovedEventHandler.MAX_WEBHOOK_ID_LENGTH) {
            throw new IllegalArgumentException("transaction reference exceeds "
                    + PaymentApprovedEventHandler.MAX_WEBHOOK_ID_LENGTH + " chars: " + webhookId);
        }

        // Re-serialize the typed payload so the partner always receives the canonical camelCase shape
        // (txnRef, partnerId, schemeId, reversedAmount, currency, reversedUsd, reason, source) regardless of
        // incidental wire differences on the topic. `partnerId` in particular MUST survive: the dispatcher's
        // target resolver reads it from this body to find the endpoint and derive its signing secret.
        String deliveryPayload = toDeliveryPayload(event);

        boolean enqueued = persistenceService
                .enqueuePendingIfAbsent(webhookId, EVENT_TYPE, deliveryPayload)
                .isPresent();
        if (enqueued) {
            log.info("enqueued webhook delivery: eventType={} txnRef={} partnerId={} source={} amount={} {}",
                    EVENT_TYPE, webhookId, event.partnerId(), event.source(), event.reversedAmount(),
                    event.currency());
        } else {
            log.info("duplicate payment.reversed event skipped (already enqueued): txnRef={}", webhookId);
        }
        return enqueued;
    }

    private String toDeliveryPayload(PaymentReversedPayload event) {
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
