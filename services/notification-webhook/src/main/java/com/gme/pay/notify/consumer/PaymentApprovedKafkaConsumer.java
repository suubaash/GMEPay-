package com.gme.pay.notify.consumer;

import com.gme.pay.events.kafka.KafkaEventPublisher;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Objects;

/**
 * Kafka entry point for {@code gmepay.payment.approved} (17.4-G04).
 *
 * <p>Registered as a bean only by {@link WebhookKafkaConsumerConfig} (gated on
 * {@code spring.kafka.bootstrap-servers}) &mdash; deliberately <em>not</em> a
 * {@code @Component}, so unit slices without a broker never create a listener
 * container.
 *
 * <p>Ack mode is {@code MANUAL}: the offset is committed only <em>after</em> the
 * delivery attempt has been durably enqueued in {@code webhook_delivery_log}.
 * If the handler throws, the record is not acked here; the container's
 * {@code DefaultErrorHandler} retries and finally dead-letters poison records to
 * {@value #DLT_TOPIC}.
 */
public class PaymentApprovedKafkaConsumer {

    /** {@code gmepay.payment.approved} — topic naming convention from lib-events-kafka. */
    public static final String TOPIC = KafkaEventPublisher.TOPIC_PREFIX + "payment.approved";

    /** Dead-letter topic ({@code DeadLetterPublishingRecoverer}'s default {@code .DLT} suffix). */
    public static final String DLT_TOPIC = TOPIC + ".DLT";

    /** Consumer group for this service. */
    public static final String GROUP_ID = "notification-webhook";

    private static final Logger log = LoggerFactory.getLogger(PaymentApprovedKafkaConsumer.class);

    private final PaymentApprovedEventHandler handler;

    public PaymentApprovedKafkaConsumer(PaymentApprovedEventHandler handler) {
        this.handler = Objects.requireNonNull(handler);
    }

    @KafkaListener(
            topics = TOPIC,
            groupId = GROUP_ID,
            containerFactory = WebhookKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY
    )
    public void onPaymentApproved(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.debug("consumed payment.approved record: key={} partition={} offset={}",
                record.key(), record.partition(), record.offset());
        // May throw — in that case we do NOT ack; the error handler takes over.
        handler.handle(record.key(), record.value());
        ack.acknowledge();
    }
}
