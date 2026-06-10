package com.gme.pay.events.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Docker-backed IT: publishes a domain event through {@link KafkaEventPublisher} against a
 * real Kafka broker (Testcontainers) and reads it back with a plain {@link KafkaConsumer},
 * asserting topic, key (aggregateId) and the JSON payload contract (money as plain string,
 * occurredAt as ISO-8601). Excluded from the plain `test` task (tag "docker"); CI runs it
 * via `integrationTest`.
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
class KafkaEventPublisherIT {

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @Test
    void publishedEventIsConsumableWithExpectedTopicKeyAndPayload() throws Exception {
        Map<String, Object> producerConfig = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        KafkaTemplate<String, String> template =
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerConfig));
        try {
            new KafkaEventPublisher(template).publish(PaymentApprovedTestEvent.sample());
        } finally {
            template.destroy();
        }

        ConsumerRecord<String, String> record = consumeOne("gmepay.payment.approved");

        assertEquals("gmepay.payment.approved", record.topic());
        assertEquals("txn-0001", record.key(), "record key must be the aggregateId");

        JsonNode payload = new ObjectMapper().readTree(record.value());
        assertEquals("payment.approved", payload.get("eventType").asText());
        assertEquals("txn-0001", payload.get("aggregateId").asText());
        assertEquals("2026-06-10T08:30:00Z", payload.get("occurredAt").asText());
        assertTrue(payload.get("amount").isTextual(), "money must be a JSON string per MONEY_CONVENTION");
        assertEquals("10.20", payload.get("amount").asText());
        assertEquals("USD", payload.get("currency").asText());
    }

    private static ConsumerRecord<String, String> consumeOne(String topic) {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "gmepay-it-" + System.nanoTime());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            List<ConsumerRecord<String, String>> received = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                polled.records(topic).forEach(received::add);
                if (!received.isEmpty()) {
                    assertEquals(1, received.size(), "exactly one event expected on " + topic);
                    return received.get(0);
                }
            }
        }
        return fail("no record received on topic " + topic + " within 30s");
    }
}
