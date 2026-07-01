package com.gme.pay.bff.alert;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Objects;

/**
 * Kafka entry point for {@code gmepay.ops.alert} — closes the alert loop (#5). The Operations wave
 * (transaction-mgmt / notification-webhook / settlement-reconciliation) publishes ops alerts on this
 * topic; the BFF consumes them into the {@link OpsAlertStore} so they surface in the control tower and
 * {@code GET /v1/admin/ops/alerts}.
 *
 * <p>Registered as a bean only by {@link OpsAlertKafkaConsumerConfig} (gated on
 * {@code spring.kafka.bootstrap-servers}) — deliberately <em>not</em> a {@code @Component}, so unit
 * slices and the local default (no broker) never create a listener container. Mirrors the
 * revenue-ledger / notification-webhook consumer pattern.
 *
 * <p>Ack mode is {@code MANUAL}: the offset is committed only after the alert is stored. If the handler
 * throws, the record is not acked; the container's error handler retries then dead-letters to
 * {@value #DLT_TOPIC}.
 */
public class OpsAlertKafkaConsumer {

    /** {@code gmepay.ops.alert} — {@code gmepay.} prefix + {@code ops.alert} eventType. */
    public static final String TOPIC = "gmepay." + OpsAlertEventHandler.EVENT_TYPE;

    /** Dead-letter topic ({@code DeadLetterPublishingRecoverer}'s default {@code .DLT} suffix). */
    public static final String DLT_TOPIC = TOPIC + ".DLT";

    /** Consumer group for this service. */
    public static final String GROUP_ID = "ops-partner-bff";

    private static final Logger log = LoggerFactory.getLogger(OpsAlertKafkaConsumer.class);

    private final OpsAlertEventHandler handler;

    public OpsAlertKafkaConsumer(OpsAlertEventHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler required");
    }

    @KafkaListener(
            topics = TOPIC,
            groupId = GROUP_ID,
            containerFactory = OpsAlertKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY
    )
    public void onOpsAlert(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.debug("consumed ops.alert record: key={} partition={} offset={}",
                record.key(), record.partition(), record.offset());
        // May throw — in that case we do NOT ack; the error handler takes over.
        handler.handle(record.key(), record.value());
        ack.acknowledge();
    }
}
