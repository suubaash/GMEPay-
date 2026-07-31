package com.gme.pay.notify.consumer;

import com.gme.pay.events.kafka.KafkaEventPublisher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Objects;

/**
 * Kafka entry point for {@code gmepay.payment.reversed} — the partner refund/reversal notification (T2-6).
 *
 * <p>transaction-mgmt publishes here whenever a transaction reaches a money-terminal reversal: an explicit
 * REFUND ({@code source=REFUND}, added by T2-6) or an operator force-resolve to REVERSED
 * ({@code source=OPERATOR}). Both mean "this payment was backed out", which is what the partner is told.
 *
 * <p>Registered as a bean only by {@link WebhookKafkaConsumerConfig} (gated on
 * {@code spring.kafka.bootstrap-servers}) — deliberately <em>not</em> a {@code @Component}, so unit slices
 * without a broker never create a listener container. It reuses the same MANUAL-ack container factory and DLT
 * error handler as the {@code payment.approved} consumer, and the same consumer group, so the two share
 * offsets management and there is one notification-webhook group.
 *
 * <p>Ack mode is {@code MANUAL}: the offset is committed only <em>after</em> the delivery has been durably
 * enqueued. If the handler throws, the record is not acked; the container's error handler retries and finally
 * dead-letters to {@value #DLT_TOPIC}.
 */
public class PaymentReversedKafkaConsumer {

    /** {@code gmepay.payment.reversed} — topic naming convention from lib-events-kafka. */
    public static final String TOPIC = KafkaEventPublisher.TOPIC_PREFIX + "payment.reversed";

    /** Dead-letter topic ({@code DeadLetterPublishingRecoverer}'s default {@code .DLT} suffix). */
    public static final String DLT_TOPIC = TOPIC + ".DLT";

    /** Same consumer group as the payment.approved consumer (one notification-webhook group). */
    public static final String GROUP_ID = PaymentApprovedKafkaConsumer.GROUP_ID;

    private static final Logger log = LoggerFactory.getLogger(PaymentReversedKafkaConsumer.class);

    private final PaymentReversedEventHandler handler;

    public PaymentReversedKafkaConsumer(PaymentReversedEventHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler required");
    }

    @KafkaListener(
            topics = TOPIC,
            groupId = GROUP_ID,
            containerFactory = WebhookKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY
    )
    public void onPaymentReversed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.debug("consumed payment.reversed record: key={} partition={} offset={}",
                record.key(), record.partition(), record.offset());
        // May throw — in that case we do NOT ack; the error handler takes over.
        handler.handle(record.key(), record.value());
        ack.acknowledge();
    }
}
