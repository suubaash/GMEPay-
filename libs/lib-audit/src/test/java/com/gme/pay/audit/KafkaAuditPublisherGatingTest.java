package com.gme.pay.audit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Verifies that {@link KafkaAuditPublisher} is only registered when
 * {@code spring.kafka.bootstrap-servers} is set in the environment.  No real Kafka
 * broker is contacted — the {@link AuditPublisherAutoConfiguration} creates a lazy
 * producer factory so the bean registration succeeds without an active broker.
 */
class KafkaAuditPublisherGatingTest {

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        ctx.register(AuditPublisherAutoConfiguration.class);
        ctx.refresh();
        return ctx;
    }

    @Test
    void kafkaPublisherAbsentWithoutBootstrapServers() {
        try (var ctx = context(Map.of())) {
            assertTrue(ctx.getBeansOfType(KafkaAuditPublisher.class).isEmpty(),
                    "KafkaAuditPublisher must not be registered without bootstrap-servers");
        }
    }

    @Test
    void kafkaPublisherRegisteredWhenBootstrapServersPresent() {
        try (var ctx = context(Map.of("spring.kafka.bootstrap-servers", "localhost:9092"))) {
            assertFalse(ctx.getBeansOfType(KafkaAuditPublisher.class).isEmpty(),
                    "KafkaAuditPublisher must be registered when bootstrap-servers is set");
        }
    }

    @Test
    void logPublisherAbsentWhenKafkaPublisherPresent() {
        // When Kafka is configured, KafkaAuditPublisher is registered and satisfies
        // the @ConditionalOnMissingBean(AuditPublisher.class) guard, so LogAuditPublisher
        // is NOT registered.
        try (var ctx = context(Map.of("spring.kafka.bootstrap-servers", "localhost:9092"))) {
            assertFalse(ctx.getBeansOfType(KafkaAuditPublisher.class).isEmpty(),
                    "KafkaAuditPublisher must be present when bootstrap-servers is set");
            assertTrue(ctx.getBeansOfType(LogAuditPublisher.class).isEmpty(),
                    "LogAuditPublisher must be absent when KafkaAuditPublisher is registered");
        }
    }

    @Test
    void noDataSourceAndNoKafkaYieldsLogPublisher() {
        try (var ctx = context(Map.of())) {
            assertInstanceOf(LogAuditPublisher.class, ctx.getBean(AuditPublisher.class),
                    "LogAuditPublisher must be the sole publisher when neither DB nor Kafka is configured");
        }
    }
}
