package com.gme.pay.ledger.consumer;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.contracts.events.PaymentApprovedPayload;
import com.gme.pay.ledger.revenue.RevenueCaptureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Handles one {@code payment.approved} event consumed from {@code gmepay.payment.approved} and turns
 * it into a revenue-capture row (FX margin + service charge), via {@link RevenueCaptureService}.
 *
 * <p>This is the async ingestion path mandated by {@code docs/INTER_SERVICE_CONTRACTS.md}
 * ("revenue-ledger consumes events payment.approved … async"). It is the event-driven sibling of the
 * sync {@code POST /v1/revenue/capture} endpoint and shares the same idempotent write path, so a
 * transaction captured by either surface yields exactly one row.
 *
 * <p><b>Payload contract (Phase 2).</b> The event value is the JSON serialisation of the canonical
 * {@link PaymentApprovedPayload} (lib-api-contracts) that payment-executor now emits — we deserialize
 * directly into that DTO instead of plucking JSON fields by hand, so producer and consumer agree at
 * the type level. The DTO carries: {@code eventType}, {@code aggregateId}, {@code txnRef},
 * {@code occurredAt}, {@code revenueDate} (optional), {@code partnerId}, {@code schemeId},
 * {@code collectionMarginUsd}, {@code payoutMarginUsd}, {@code serviceChargeAmount},
 * {@code serviceChargeCcy}, {@code feeSharePct}. Money fields ride as decimal strings per
 * {@code docs/MONEY_CONVENTION.md}.
 *
 * <p><b>Defensive defaults.</b> The DTO binding is lenient about presence: a null money field maps to
 * {@code BigDecimal.ZERO}, {@code serviceChargeCcy} defaults to {@code USD}, {@code txnRef} falls back
 * to {@code aggregateId} then the Kafka record key, and {@code revenueDate} falls back to the UTC date
 * of {@code occurredAt}. This keeps the consumer resilient to older/leaner producers without abandoning
 * the strong type.
 *
 * <p><b>Poison handling.</b> Unparseable JSON, a wrong/missing {@code eventType}, a missing
 * {@code txnRef}, or invalid money raise {@link IllegalArgumentException}; the Kafka error handler
 * retries N times then dead-letters the record. The capture itself is idempotent, so retries that
 * partially succeeded never double-write.
 */
@Service
public class PaymentApprovedEventHandler {

    /** Event type this handler accepts; anything else on the topic is poison. */
    public static final String EVENT_TYPE = PaymentApprovedPayload.EVENT_TYPE;

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovedEventHandler.class);

    private final RevenueCaptureService captureService;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            // Tolerate producers that add fields ahead of us; never fail on an unknown property.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public PaymentApprovedEventHandler(RevenueCaptureService captureService) {
        this.captureService = Objects.requireNonNull(captureService, "captureService required");
    }

    /**
     * Validate one consumed record and capture its revenue.
     *
     * @param recordKey the Kafka record key (publisher sets it to the aggregate id); used as the
     *                  {@code txnRef} fallback when the payload omits one
     * @param payload   the raw JSON record value (a serialised {@link PaymentApprovedPayload})
     * @return {@code true} if a new revenue row was created, {@code false} on an idempotent skip
     * @throws IllegalArgumentException if the record is poison (invalid JSON / contract / money)
     */
    public boolean handle(String recordKey, String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payment.approved record has an empty payload");
        }

        PaymentApprovedPayload event;
        try {
            event = objectMapper.readValue(payload, PaymentApprovedPayload.class);
        } catch (com.fasterxml.jackson.databind.exc.InvalidFormatException e) {
            // A well-formed JSON object whose value cannot bind to its DTO field (e.g. a non-numeric
            // money string). Surface the offending field path so the DLT record is diagnosable.
            String field = e.getPath().isEmpty() ? "?" : e.getPath().get(e.getPath().size() - 1).getFieldName();
            throw new IllegalArgumentException(
                    "payment.approved field " + field + " has an invalid value: " + e.getValue(), e);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("payment.approved payload is not valid JSON", e);
        }
        if (event == null) {
            throw new IllegalArgumentException("payment.approved payload deserialized to null");
        }

        if (!EVENT_TYPE.equals(event.eventType())) {
            throw new IllegalArgumentException(
                    "unexpected eventType on payment.approved topic: " + event.eventType());
        }

        String txnRef = firstNonBlank(event.txnRef(), event.aggregateId(), recordKey);
        if (txnRef == null) {
            throw new IllegalArgumentException(
                    "payment.approved event has no txnRef (payload field, aggregateId or record key)");
        }

        LocalDate revenueDate = resolveRevenueDate(event);
        BigDecimal collectionMarginUsd = orZero(event.collectionMarginUsd());
        BigDecimal payoutMarginUsd = orZero(event.payoutMarginUsd());
        BigDecimal serviceChargeAmount = orZero(event.serviceChargeAmount());
        String serviceChargeCcy = firstNonBlank(event.serviceChargeCcy(), "USD");
        BigDecimal feeSharePct = orZero(event.feeSharePct());

        boolean created;
        try {
            created = captureService.capture(
                    txnRef, event.partnerId(), event.schemeId(), revenueDate,
                    collectionMarginUsd, payoutMarginUsd,
                    serviceChargeAmount, serviceChargeCcy, feeSharePct).created();
        } catch (IllegalArgumentException | NullPointerException e) {
            // Contract/money violations are poison: rethrow so the error handler dead-letters.
            throw new IllegalArgumentException(
                    "payment.approved event for txnRef=" + txnRef + " is invalid: " + e.getMessage(), e);
        }

        if (created) {
            log.info("revenue captured from payment.approved: txnRef={} partnerId={}", txnRef, event.partnerId());
        } else {
            log.info("duplicate payment.approved skipped (already captured): txnRef={}", txnRef);
        }
        return created;
    }

    /** Use the explicit {@code revenueDate} when present, else the UTC date of {@code occurredAt}. */
    private LocalDate resolveRevenueDate(PaymentApprovedPayload event) {
        if (event.revenueDate() != null) {
            return event.revenueDate();
        }
        if (event.occurredAt() != null) {
            return event.occurredAt().atZone(ZoneOffset.UTC).toLocalDate();
        }
        throw new IllegalArgumentException("payment.approved event has neither revenueDate nor occurredAt");
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
