package com.gme.pay.bff.alert;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;

import java.util.Objects;

/**
 * Kafka entry point for {@code gmepay.settlement.completed} — the consumer that closes the CTO
 * gate's orphan finding: settlement-reconciliation's outbox published this terminal money event
 * onto the bus and NOTHING received it. Every generated settlement batch now lands in the ops
 * alert surface (control tower / Alerts tab) as an INFO signal, so operators see each ZP0061/0063
 * file the moment it is booked, with its net amount and checksum.
 *
 * <p>Registered by {@link OpsAlertKafkaConsumerConfig} (gated on
 * {@code spring.kafka.bootstrap-servers}); MANUAL ack + DLT semantics identical to
 * {@link OpsAlertKafkaConsumer}.
 */
public class SettlementCompletedKafkaConsumer {

    /** {@code gmepay.settlement.completed} — {@code gmepay.} prefix + eventType. */
    public static final String TOPIC = "gmepay." + SettlementCompletedEventHandler.EVENT_TYPE;

    /** Dead-letter topic ({@code DeadLetterPublishingRecoverer}'s default {@code .DLT} suffix). */
    public static final String DLT_TOPIC = TOPIC + ".DLT";

    /** Consumer group for this service. */
    public static final String GROUP_ID = "ops-partner-bff";

    private static final Logger log = LoggerFactory.getLogger(SettlementCompletedKafkaConsumer.class);

    private final SettlementCompletedEventHandler handler;

    public SettlementCompletedKafkaConsumer(SettlementCompletedEventHandler handler) {
        this.handler = Objects.requireNonNull(handler, "handler required");
    }

    @KafkaListener(
            topics = TOPIC,
            groupId = GROUP_ID,
            containerFactory = OpsAlertKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY
    )
    public void onSettlementCompleted(ConsumerRecord<String, String> record, Acknowledgment ack) {
        log.debug("consumed settlement.completed record: key={} partition={} offset={}",
                record.key(), record.partition(), record.offset());
        handler.handle(record.key(), record.value());
        ack.acknowledge();
    }
}
