package com.gme.pay.ledger.consumer;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.ledger.revenue.RevenueCaptureService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
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
 * <p><b>Payload contract.</b> The event value is the JSON-serialised domain event. Beyond the
 * {@code DomainEvent} base fields ({@code eventType}, {@code aggregateId}, {@code occurredAt}) the
 * revenue fields ride as components: {@code txnRef} (falls back to {@code aggregateId}),
 * {@code partnerId}, {@code schemeId}, {@code collectionMarginUsd}, {@code payoutMarginUsd},
 * {@code serviceChargeAmount}, {@code serviceChargeCcy}, {@code feeSharePct}, and optional
 * {@code revenueDate} (falls back to {@code occurredAt}'s UTC date). Money fields ride as decimal
 * strings per {@code docs/MONEY_CONVENTION.md}.
 *
 * <p><b>Poison handling.</b> Unparseable JSON, a wrong/missing {@code eventType}, a missing
 * {@code txnRef}, or invalid money raise {@link IllegalArgumentException}; the Kafka error handler
 * retries N times then dead-letters the record. The capture itself is idempotent, so retries that
 * partially succeeded never double-write.
 */
@Service
public class PaymentApprovedEventHandler {

    /** Event type this handler accepts; anything else on the topic is poison. */
    public static final String EVENT_TYPE = "payment.approved";

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovedEventHandler.class);

    private final RevenueCaptureService captureService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PaymentApprovedEventHandler(RevenueCaptureService captureService) {
        this.captureService = Objects.requireNonNull(captureService, "captureService required");
    }

    /**
     * Validate one consumed record and capture its revenue.
     *
     * @param recordKey the Kafka record key (publisher sets it to the aggregate id); used as the
     *                  {@code txnRef} fallback when the payload omits one
     * @param payload   the raw JSON record value
     * @return {@code true} if a new revenue row was created, {@code false} on an idempotent skip
     * @throws IllegalArgumentException if the record is poison (invalid JSON / contract / money)
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

        String txnRef = firstNonBlank(textOrNull(root, "txnRef"),
                textOrNull(root, "aggregateId"), recordKey);
        if (txnRef == null) {
            throw new IllegalArgumentException(
                    "payment.approved event has no txnRef (payload field, aggregateId or record key)");
        }

        long partnerId = longOrZero(root, "partnerId");
        long schemeId = longOrZero(root, "schemeId");
        LocalDate revenueDate = resolveRevenueDate(root);
        BigDecimal collectionMarginUsd = decimalOrZero(root, "collectionMarginUsd");
        BigDecimal payoutMarginUsd = decimalOrZero(root, "payoutMarginUsd");
        BigDecimal serviceChargeAmount = decimalOrZero(root, "serviceChargeAmount");
        String serviceChargeCcy = firstNonBlank(textOrNull(root, "serviceChargeCcy"), "USD");
        BigDecimal feeSharePct = decimalOrZero(root, "feeSharePct");

        boolean created;
        try {
            created = captureService.capture(
                    txnRef, partnerId, schemeId, revenueDate,
                    collectionMarginUsd, payoutMarginUsd,
                    serviceChargeAmount, serviceChargeCcy, feeSharePct).created();
        } catch (IllegalArgumentException | NullPointerException e) {
            // Contract/money violations are poison: rethrow so the error handler dead-letters.
            throw new IllegalArgumentException(
                    "payment.approved event for txnRef=" + txnRef + " is invalid: " + e.getMessage(), e);
        }

        if (created) {
            log.info("revenue captured from payment.approved: txnRef={} partnerId={}", txnRef, partnerId);
        } else {
            log.info("duplicate payment.approved skipped (already captured): txnRef={}", txnRef);
        }
        return created;
    }

    /** Use the explicit {@code revenueDate} when present, else the UTC date of {@code occurredAt}. */
    private LocalDate resolveRevenueDate(JsonNode root) {
        String explicit = textOrNull(root, "revenueDate");
        if (explicit != null && !explicit.isBlank()) {
            try {
                return LocalDate.parse(explicit);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("payment.approved revenueDate is not an ISO date: " + explicit, e);
            }
        }
        String occurredAt = textOrNull(root, "occurredAt");
        if (occurredAt != null && !occurredAt.isBlank()) {
            try {
                return Instant.parse(occurredAt).atZone(ZoneOffset.UTC).toLocalDate();
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("payment.approved occurredAt is not an ISO instant: " + occurredAt, e);
            }
        }
        throw new IllegalArgumentException("payment.approved event has neither revenueDate nor occurredAt");
    }

    private static String textOrNull(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static long longOrZero(JsonNode root, String field) {
        JsonNode node = root.get(field);
        return node == null || node.isNull() ? 0L : node.asLong();
    }

    private static BigDecimal decimalOrZero(JsonNode root, String field) {
        JsonNode node = root.get(field);
        if (node == null || node.isNull()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("payment.approved field " + field + " is not a number: " + node.asText(), e);
        }
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
