package com.gme.pay.ledger.consumer;

import com.gme.pay.events.kafka.KafkaEventPublisher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Objects;

/**
 * Kafka entry point for {@code gmepay.payment.approved} — the async revenue-capture path mandated by
 * {@code docs/INTER_SERVICE_CONTRACTS.md} (payment-executor publishes, revenue-ledger consumes for
 * "margin + fee capture").
 *
 * <p>Registered as a bean only by {@link RevenueLedgerKafkaConsumerConfig} (gated on
 * {@code spring.kafka.bootstrap-servers}) — deliberately <em>not</em> a {@code @Component}, so unit
 * slices and the local default (no broker) never create a listener container.
 *
 * <p>Ack mode is {@code MANUAL}: the offset is committed only <em>after</em> the revenue row has been
 * durably persisted. If the handler throws, the record is not acked here; the container's
 * {@link org.springframework.kafka.listener.DefaultErrorHandler} retries and finally dead-letters
 * poison records to {@value #DLT_TOPIC}.
 */
public class PaymentApprovedKafkaConsumer {

    /** {@code gmepay.payment.approved} — topic naming convention from lib-events-kafka. */
    public static final String TOPIC = KafkaEventPublisher.TOPIC_PREFIX + "payment.approved";

    /** Dead-letter topic ({@code DeadLetterPublishingRecoverer}'s default {@code .DLT} suffix). */
    public static final String DLT_TOPIC = TOPIC + ".DLT";

    /** Consumer group for this service (distinct from notification-webhook's own group). */
    public static final String GROUP_ID = "revenue-ledger";

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovedKafkaConsumer.class);

    private final PaymentApprovedEventHandler handler;

    public PaymentApprovedKafkaConsumer(PaymentApprovedEventHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler required");
    }

    @KafkaListener(
            topics = TOPIC,
            groupId = GROUP_ID,
            containerFactory = RevenueLedgerKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY
    )
    public void onPaymentApproved(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.debug("consumed payment.approved record: key={} partition={} offset={}",
                record.key(), record.partition(), record.offset());
        // May throw — in that case we do NOT ack; the error handler takes over.
        handler.handle(record.key(), record.value());
        ack.acknowledge();
    }
}
