package com.gme.pay.ledger.consumer;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gme.pay.contracts.events.PaymentReversedPayload;
import com.gme.pay.ledger.domain.ledger.RevenueReversalService;
import com.gme.pay.ledger.domain.model.Journal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * Handles one {@code payment.reversed} event consumed from {@code gmepay.payment.reversed} and turns it
 * into a <b>reversing journal</b> — a balanced contra-entry that backs out the original revenue capture
 * for that {@code txnRef} (margin + service charge + fee-share), via {@link RevenueReversalService}.
 *
 * <p><b>Why this exists.</b> An operator force-resolve of an {@code UNCERTAIN} txn to {@code REVERSED}
 * (and any scheme/timeout reversal) now emits {@link PaymentReversedPayload} on
 * {@code gmepay.payment.reversed}. Before this handler, that transition left the captured revenue on the
 * books: money was reversed with no ledger impact. This handler is the async sibling of the
 * {@code payment.approved} → revenue-capture consumer and reuses the same broker-free /
 * gated-Kafka wiring ({@link RevenueLedgerKafkaConsumerConfig}).
 *
 * <p><b>Idempotent on {@code txnRef}.</b> A repeat {@code payment.reversed} for a txnRef whose capture
 * was already reversed is a no-op — {@link RevenueReversalService} short-circuits, so redelivery under
 * Kafka at-least-once never double-reverses.
 *
 * <p><b>No original capture.</b> A reversal for a txnRef with no prior capture journal is a safe no-op:
 * it is logged and acked (never dead-lettered), because a reversal that arrives before / without a
 * capture is a benign ordering artifact, not a poison record.
 *
 * <p><b>Poison handling.</b> Unparseable JSON, a wrong/missing {@code eventType}, or a missing
 * {@code txnRef} raise {@link IllegalArgumentException}; the Kafka error handler retries N times then
 * dead-letters. The reversal itself is idempotent, so retries never double-book.
 */
@Service
public class PaymentReversedEventHandler {

    /** Event type this handler accepts; anything else on the topic is poison. */
    public static final String EVENT_TYPE = PaymentReversedPayload.EVENT_TYPE;

    private static final Logger log = LoggerFactory.getLogger(PaymentReversedEventHandler.class);

    private final RevenueReversalService reversalService;
    private final ObjectMapper objectMapper = new ObjectMapper()
            // Tolerate producers that add fields ahead of us; never fail on an unknown property.
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public PaymentReversedEventHandler(RevenueReversalService reversalService) {
        this.reversalService = Objects.requireNonNull(reversalService, "reversalService required");
    }

    /**
     * Validate one consumed record and book its reversing journal.
     *
     * @param recordKey the Kafka record key (publisher sets it to the aggregate id); used as the
     *                  {@code txnRef} fallback when the payload omits one
     * @param payload   the raw JSON record value (a serialised {@link PaymentReversedPayload})
     * @return {@code true} if a reversing journal was newly booked, {@code false} on an idempotent skip
     *         or when there was no original capture to reverse
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
            throw new IllegalArgumentException("payment.reversed payload is not valid JSON", e);
        }
        if (event == null) {
            throw new IllegalArgumentException("payment.reversed payload deserialized to null");
        }

        if (!EVENT_TYPE.equals(event.eventType())) {
            throw new IllegalArgumentException(
                    "unexpected eventType on payment.reversed topic: " + event.eventType());
        }

        String txnRef = firstNonBlank(event.txnRef(), recordKey);
        if (txnRef == null) {
            throw new IllegalArgumentException(
                    "payment.reversed event has no txnRef (payload field or record key)");
        }

        Optional<Journal> reversed = reversalService.reverseCapture(txnRef);
        if (reversed.isEmpty()) {
            // Either no original capture exists (benign, e.g. reversal before capture) or the capture
            // was already reversed (idempotent skip). Both are safe no-ops — ack and move on.
            log.info("payment.reversed booked no reversing journal (no capture or already reversed): txnRef={}",
                    txnRef);
            return false;
        }
        log.info("reversing journal booked from payment.reversed: txnRef={} journalId={} reason={}",
                txnRef, reversed.get().journalId(), event.reason());
        return true;
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
