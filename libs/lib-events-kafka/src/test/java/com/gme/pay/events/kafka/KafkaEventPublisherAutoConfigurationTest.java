package com.gme.pay.events.kafka;

import com.gme.pay.events.EventPublisher;
import com.gme.pay.events.LogEventPublisher;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conditional-wiring tests using a plain {@link AnnotationConfigApplicationContext}
 * (no broker is contacted — producer creation is lazy). Verifies the gate
 * ({@code spring.kafka.bootstrap-servers}), the {@code @Primary} override of a
 * service-local {@link LogEventPublisher}, and the hardened producer settings.
 */
class KafkaEventPublisherAutoConfigurationTest {

    /** Mimics a consuming service that wires the lib-events fallback publisher. */
    @Configuration(proxyBeanMethods = false)
    static class ServiceWithLogPublisher {
        @Bean
        EventPublisher logEventPublisher() {
            return new LogEventPublisher();
        }
    }

    private static AnnotationConfigApplicationContext context(Map<String, Object> properties,
                                                              Class<?>... extraConfigs) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        ctx.register(KafkaEventPublisherAutoConfiguration.class);
        if (extraConfigs.length > 0) {
            ctx.register(extraConfigs);
        }
        ctx.refresh();
        return ctx;
    }

    @Test
    @DisplayName("with spring.kafka.bootstrap-servers set, KafkaEventPublisher is registered")
    void registersWhenBootstrapServersPresent() {
        try (var ctx = context(Map.of("spring.kafka.bootstrap-servers", "localhost:9092"))) {
            assertInstanceOf(KafkaEventPublisher.class, ctx.getBean(EventPublisher.class));
        }
    }

    @Test
    @DisplayName("KafkaEventPublisher is @Primary: it wins over a service-local LogEventPublisher")
    void primaryOverridesLogPublisher() {
        try (var ctx = context(Map.of("spring.kafka.bootstrap-servers", "localhost:9092"),
                ServiceWithLogPublisher.class)) {
            assertEquals(2, ctx.getBeansOfType(EventPublisher.class).size(),
                    "both publishers coexist; @Primary decides injection");
            assertInstanceOf(KafkaEventPublisher.class, ctx.getBean(EventPublisher.class));
        }
    }

    @Test
    @DisplayName("without the property, auto-config backs off and the LogEventPublisher remains")
    void backsOffWithoutBootstrapServers() {
        try (var ctx = context(Map.of(), ServiceWithLogPublisher.class)) {
            assertTrue(ctx.getBeansOfType(KafkaEventPublisher.class).isEmpty(),
                    "no Kafka publisher without a configured broker");
            assertInstanceOf(LogEventPublisher.class, ctx.getBean(EventPublisher.class));
        }
    }

    @Test
    @DisplayName("dedicated producer is hardened: acks=all and enable.idempotence=true")
    void producerIsAcksAllAndIdempotent() {
        try (var ctx = context(Map.of("spring.kafka.bootstrap-servers", "broker-a:9092,broker-b:9092"))) {
            @SuppressWarnings("unchecked")
            KafkaTemplate<String, String> template = (KafkaTemplate<String, String>)
                    ctx.getBean(KafkaEventPublisherAutoConfiguration.TEMPLATE_BEAN_NAME, KafkaTemplate.class);
            Map<String, Object> config = template.getProducerFactory().getConfigurationProperties();

            assertEquals("all", config.get(ProducerConfig.ACKS_CONFIG));
            assertEquals(true, config.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
            assertEquals(java.util.List.of("broker-a:9092", "broker-b:9092"),
                    config.get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG));
        }
    }
}
