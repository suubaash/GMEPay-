package com.gme.pay.prefunding.consumer;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gme.pay.contracts.events.PaymentReversedPayload;
import com.gme.pay.prefunding.service.PrefundingService;
import java.math.BigDecimal;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Handles one {@code payment.reversed} event and releases the held prefund float for it (#1).
 *
 * <p><b>Why this exists.</b> When a payment's terminal outcome becomes {@code REVERSED} — including an
 * operator force-resolve of an {@code UNCERTAIN} txn — the held prefund USD used to be lost: no signal
 * reached prefunding, so the float was never returned. This handler consumes the canonical
 * {@link PaymentReversedPayload} (topic {@code gmepay.payment.reversed}) and credits {@code reversedUsd}
 * back onto the partner's balance via {@link PrefundingService#releaseReversedFloat}, closing the leak.
 *
 * <p><b>Idempotent + at-least-once.</b> {@code releaseReversedFloat} is idempotent on {@code txnRef}
 * (a CREDIT tagged with the txnRef IS the reversal marker — shared with the operator {@code reverse}
 * path), so a Kafka redelivery, or a reversal that an operator already handled, never double-credits.
 *
 * <p><b>Defensive.</b> A null/absent {@code reversedUsd} is logged and skipped (nothing to release).
 * Unparseable JSON, a wrong/missing {@code eventType}, or a missing {@code txnRef}/{@code partnerId}
 * raise {@link IllegalArgumentException} so the Kafka error handler retries then dead-letters — the
 * partition never wedges on a poison record.
 */
@Service
public class PaymentReversedEventHandler {

    /** Event type this handler accepts; anything else on the topic is poison. */
    public static final String EVENT_TYPE = PaymentReversedPayload.EVENT_TYPE;

    private static final Logger log = LoggerFactory.getLogger(PaymentReversedEventHandler.class);

    private final PrefundingService service;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public PaymentReversedEventHandler(PrefundingService service) {
        this.service = Objects.requireNonNull(service, "service required");
    }

    /**
     * Validate one consumed record and release its held float.
     *
     * @param recordKey the Kafka record key (publisher sets it to the aggregate id); {@code partnerId}
     *                  fallback is intentionally NOT taken from here — a missing partnerId is poison.
     * @param payload   the raw JSON record value (a serialised {@link PaymentReversedPayload})
     * @return the USD actually released ({@code ZERO} on an idempotent skip / null reversedUsd)
     * @throws IllegalArgumentException if the record is poison (invalid JSON / contract)
     */
    public BigDecimal handle(String recordKey, String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payment.reversed record has an empty payload");
        }
        PaymentReversedPayload event;
        try {
            event = objectMapper.readValue(payload, PaymentReversedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("payment.reversed record is not valid JSON: " + ex.getMessage(), ex);
        }
        if (event.eventType() != null && !EVENT_TYPE.equals(event.eventType())) {
            throw new IllegalArgumentException("unexpected eventType on gmepay.payment.reversed: " + event.eventType());
        }
        String txnRef = event.txnRef();
        String partnerId = event.partnerId();
        if (txnRef == null || txnRef.isBlank()) {
            throw new IllegalArgumentException("payment.reversed record has no txnRef");
        }
        if (partnerId == null || partnerId.isBlank()) {
            throw new IllegalArgumentException("payment.reversed record has no partnerId (txnRef=" + txnRef + ")");
        }

        BigDecimal reversedUsd = parseUsd(event.reversedUsd());
        if (reversedUsd == null) {
            log.info("payment.reversed with null/absent reversedUsd — no float to release: txnRef={} partner={}",
                    txnRef, partnerId);
            return BigDecimal.ZERO;
        }

        PrefundingService.ReverseResult result = service.releaseReversedFloat(partnerId, txnRef, reversedUsd);
        log.info("released reversed float: partner={} txnRef={} reversedUsd={} credited={}",
                partnerId, txnRef, reversedUsd.toPlainString(), result.reversedAmount().toPlainString());
        return result.reversedAmount();
    }

    /** Parse a decimal-string USD amount (money convention); null/blank ⇒ null (no-op). Poison if malformed. */
    private static BigDecimal parseUsd(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("payment.reversed reversedUsd is not a valid decimal: " + raw, ex);
        }
    }
}
