package com.gme.pay.notify.consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@code @ConditionalOnProperty("spring.kafka.bootstrap-servers")}
 * gate on {@link WebhookKafkaConsumerConfig}: without the property (the local /
 * unit-slice default) no Kafka consumer beans are created at all, so tests and
 * broker-less local runs never attempt a connection.
 */
class WebhookKafkaConsumerConfigGatingTest {

    @Test
    @DisplayName("no spring.kafka.bootstrap-servers -> no listener, factory or DLT producer beans")
    void backsOffWithoutBootstrapServers() {
        new ApplicationContextRunner()
                .withUserConfiguration(WebhookKafkaConsumerConfig.class)
                .run(context -> {
                    assertTrue(context.getStartupFailure() == null, "context must load cleanly");
                    assertFalse(context.containsBean("paymentApprovedKafkaConsumer"));
                    assertFalse(context.containsBean(WebhookKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY));
                    assertFalse(context.containsBean("webhookConsumerFactory"));
                    assertFalse(context.containsBean(WebhookKafkaConsumerConfig.DLT_TEMPLATE_BEAN_NAME));
                    assertFalse(context.containsBean("webhookKafkaErrorHandler"));
                });
    }
}
