package com.gme.pay.ledger.consumer;

import com.gme.pay.events.kafka.KafkaEventPublisher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Objects;

/**
 * Kafka entry point for {@code gmepay.payment.reversed} — the async reversing-journal path. When a
 * payment's terminal outcome becomes {@code REVERSED} (including an operator force-resolve of an
 * {@code UNCERTAIN} txn), transaction-mgmt publishes {@code PaymentReversedPayload} here and
 * revenue-ledger books a balanced contra of the original revenue capture.
 *
 * <p>Registered as a bean only by {@link RevenueLedgerKafkaConsumerConfig} (gated on
 * {@code spring.kafka.bootstrap-servers}) — deliberately <em>not</em> a {@code @Component}, so unit
 * slices and the local default (no broker) never create a listener container. It reuses the same
 * MANUAL-ack container factory + DLT error handler as the {@code payment.approved} consumer.
 *
 * <p>Ack mode is {@code MANUAL}: the offset is committed only <em>after</em> the reversing journal has
 * been durably posted (or safely no-op'd). If the handler throws, the record is not acked; the
 * container's error handler retries and finally dead-letters poison records to {@value #DLT_TOPIC}.
 */
public class PaymentReversedKafkaConsumer {

    /** {@code gmepay.payment.reversed} — topic naming convention from lib-events-kafka. */
    public static final String TOPIC = KafkaEventPublisher.TOPIC_PREFIX + "payment.reversed";

    /** Dead-letter topic ({@code DeadLetterPublishingRecoverer}'s default {@code .DLT} suffix). */
    public static final String DLT_TOPIC = TOPIC + ".DLT";

    /** Same consumer group as the payment.approved consumer (one revenue-ledger group). */
    public static final String GROUP_ID = "revenue-ledger";

    private static final Logger log = LoggerFactory.getLogger(PaymentReversedKafkaConsumer.class);

    private final PaymentReversedEventHandler handler;

    public PaymentReversedKafkaConsumer(PaymentReversedEventHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler required");
    }

    @KafkaListener(
            topics = TOPIC,
            groupId = GROUP_ID,
            containerFactory = RevenueLedgerKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY
    )
    public void onPaymentReversed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.debug("consumed payment.reversed record: key={} partition={} offset={}",
                record.key(), record.partition(), record.offset());
        // May throw — in that case we do NOT ack; the error handler takes over.
        handler.handle(record.key(), record.value());
        ack.acknowledge();
    }
}
