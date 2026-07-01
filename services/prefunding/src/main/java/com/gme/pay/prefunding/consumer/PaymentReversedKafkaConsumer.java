package com.gme.pay.prefunding.consumer;

import com.gme.pay.events.kafka.KafkaEventPublisher;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

/**
 * Kafka entry point for {@code gmepay.payment.reversed} — the release-on-reversal path (#1).
 * payment-executor / ops force-resolve publishes; prefunding consumes to return the held float.
 *
 * <p>Registered as a bean only by {@link PrefundingKafkaConsumerConfig} (gated on
 * {@code spring.kafka.bootstrap-servers}) — deliberately <em>not</em> a {@code @Component}, so unit
 * slices and the local default (no broker) never create a listener container.
 *
 * <p>Ack mode is {@code MANUAL}: the offset is committed only <em>after</em> the float has been
 * released/credited. If the handler throws, the record is not acked; the container's
 * {@link org.springframework.kafka.listener.DefaultErrorHandler} retries and finally dead-letters
 * poison records to {@value #DLT_TOPIC}.
 */
public class PaymentReversedKafkaConsumer {

    /** {@code gmepay.payment.reversed} — topic naming convention from lib-events-kafka. */
    public static final String TOPIC = KafkaEventPublisher.TOPIC_PREFIX + "payment.reversed";

    /** Dead-letter topic ({@code DeadLetterPublishingRecoverer}'s default {@code .DLT} suffix). */
    public static final String DLT_TOPIC = TOPIC + ".DLT";

    /** Consumer group for this service. */
    public static final String GROUP_ID = "prefunding";

    private static final Logger log = LoggerFactory.getLogger(PaymentReversedKafkaConsumer.class);

    private final PaymentReversedEventHandler handler;

    public PaymentReversedKafkaConsumer(PaymentReversedEventHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler required");
    }

    @KafkaListener(
            topics = TOPIC,
            groupId = GROUP_ID,
            containerFactory = PrefundingKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY
    )
    public void onPaymentReversed(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.debug("consumed payment.reversed record: key={} partition={} offset={}",
                record.key(), record.partition(), record.offset());
        // May throw — in that case we do NOT ack; the error handler takes over.
        handler.handle(record.key(), record.value());
        ack.acknowledge();
    }
}
