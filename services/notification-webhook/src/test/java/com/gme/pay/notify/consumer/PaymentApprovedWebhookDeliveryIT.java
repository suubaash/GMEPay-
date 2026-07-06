package com.gme.pay.notify.consumer;

import com.gme.pay.notify.domain.WebhookHttpClient;
import com.gme.pay.notify.domain.WebhookSender;
import com.gme.pay.notify.persistence.WebhookDeliveryEntity;
import com.gme.pay.notify.persistence.WebhookDeliveryRepository;
import com.gme.pay.notify.persistence.WebhookEndpointEntity;
import com.gme.pay.notify.persistence.WebhookEndpointRepository;
import com.gme.pay.notify.persistence.WebhookPersistenceService;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Proves the previously-dark webhook leg end-to-end (checklist §4): a
 * {@code payment.approved} record on the REAL Kafka topic is consumed by the real
 * listener container, enqueued as a PENDING row in {@code webhook_delivery_log},
 * picked up by the real scheduled {@link com.gme.pay.notify.dispatcher.WebhookDispatcher},
 * resolved against a real {@code webhook_endpoint} row, HMAC-signed by the real
 * {@link WebhookSender} — and the signed request reaches the partner-facing HTTP port.
 *
 * <p>The ONLY test double is {@link WebhookHttpClient} (the outbound socket), which the
 * production {@code RestWebhookHttpClient} implements and which already has its own
 * coverage — everything upstream of the socket is production wiring. The double records
 * the fully-signed {@link WebhookSender.WebhookRequest} and returns 200, letting the test
 * also assert the row is promoted to DELIVERED.
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "gmepay.webhook.dispatcher.enabled=true",
        "gmepay.webhook.dispatcher.interval-ms=300",
        "gmepay.webhook.dispatcher.initial-delay-ms=300",
        "gmepay.webhook.environment=SANDBOX",
        "gmepay.webhook.signing-secret=it-signing-secret"
})
class PaymentApprovedWebhookDeliveryIT {

    @Container
    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    /** Records every signed outbound request and answers 200 OK. */
    static final List<WebhookSender.WebhookRequest> SENT = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class RecordingHttpClientConfig {
        @Bean
        @Primary
        WebhookHttpClient recordingWebhookHttpClient() {
            return request -> {
                SENT.add(request);
                return WebhookSender.WebhookDeliveryResult.of(200, "ok", 5);
            };
        }
    }

    @Autowired
    private WebhookEndpointRepository endpointRepository;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Test
    void paymentApprovedOnKafka_isDeliveredAsSignedWebhook() throws Exception {
        // Partner 77 has one ACTIVE SANDBOX endpoint (HTTPS — the domain rejects anything else).
        WebhookEndpointEntity endpoint = new WebhookEndpointEntity();
        endpoint.setPartnerId(77L);
        endpoint.setWebhookUrl("https://partner-77.example.com/webhooks/gmepay");
        endpoint.setEnvironment("SANDBOX");
        endpoint.setActive(true);
        // created_at/updated_at are NOT NULL with no @PrePersist — the entity expects callers to stamp.
        endpoint.setCreatedAt(Instant.now());
        endpoint.setUpdatedAt(Instant.now());
        endpointRepository.save(endpoint);

        String txnRef = "TXN-IT-" + System.nanoTime();
        String payload = """
                {"eventType":"payment.approved","aggregateId":"%s","txnRef":"%s",
                 "occurredAt":"%s","revenueDate":"2026-07-05","partnerId":77,"schemeId":1,
                 "collectionMarginUsd":"0.25000000","payoutMarginUsd":"0.10000000",
                 "serviceChargeAmount":"500","serviceChargeCcy":"KRW","feeSharePct":"70"}
                """.formatted(txnRef, txnRef, Instant.now());

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(PaymentApprovedKafkaConsumer.TOPIC, txnRef, payload))
                    .get();
        }

        // consume -> enqueue PENDING -> scheduled drain -> signed send -> DELIVERED
        WebhookDeliveryEntity delivered = awaitDelivered(txnRef, Duration.ofSeconds(60));

        assertEquals(WebhookPersistenceService.STATUS_DELIVERED, delivered.getStatus());
        assertEquals(PaymentApprovedEventHandler.EVENT_TYPE, delivered.getEventType());
        assertNotNull(delivered.getDeliveredAt(), "DELIVERED row must carry a delivery timestamp");

        WebhookSender.WebhookRequest sent = SENT.stream()
                .filter(r -> new String(r.payload(), StandardCharsets.UTF_8).contains(txnRef))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no outbound webhook captured for " + txnRef));
        assertEquals("https://partner-77.example.com/webhooks/gmepay", sent.targetUrl());
        assertNotNull(sent.signatureHeader(), "outbound webhook must be HMAC-signed");
        assertTrue(sent.signatureHeader().length() >= 32, "signature header looks too short to be real");
        assertNotNull(sent.timestampHeader(), "outbound webhook must carry the signed timestamp");
        String body = new String(sent.payload(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"serviceChargeAmount\":\"500\""),
                "canonical money fields must survive the whole chain, got: " + body);
    }

    private WebhookDeliveryEntity awaitDelivered(String txnRef, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        Optional<WebhookDeliveryEntity> last = Optional.empty();
        while (Instant.now().isBefore(deadline)) {
            last = deliveryRepository.findAll().stream()
                    .filter(row -> row.getPayload() != null && row.getPayload().contains(txnRef))
                    .findFirst();
            if (last.isPresent()
                    && WebhookPersistenceService.STATUS_DELIVERED.equals(last.get().getStatus())) {
                return last.get();
            }
            Thread.sleep(250);
        }
        fail("webhook for " + txnRef + " not DELIVERED within " + timeout + "; last state: "
                + last.map(WebhookDeliveryEntity::getStatus).orElse("no delivery row enqueued")
                + " (sent so far: " + SENT.size() + ")");
        return null; // unreachable
    }
}
