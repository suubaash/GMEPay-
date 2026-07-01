package com.gme.pay.bff.alert;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@code @ConditionalOnProperty("spring.kafka.bootstrap-servers")} gate on
 * {@link OpsAlertKafkaConsumerConfig}: without the property (the local / unit-slice default,
 * i.e. no broker) NO Kafka consumer beans are created, so the BFF boots + the alert store /
 * endpoint still work with an empty list. With the property present, the beans wire up.
 */
class OpsAlertKafkaConsumerConfigGatingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(OpsAlertStore.class, () -> new OpsAlertStore(200))
            .withBean(OpsAlertEventHandler.class)
            .withUserConfiguration(OpsAlertKafkaConsumerConfig.class);

    @Test
    @DisplayName("no bootstrap-servers -> no listener/factory/DLT beans (no-broker fallback)")
    void backsOffWithoutBootstrapServers() {
        runner.run(context -> {
            assertTrue(context.getStartupFailure() == null, "context must load cleanly");
            assertFalse(context.containsBean("opsAlertKafkaConsumer"));
            assertFalse(context.containsBean(OpsAlertKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY));
            assertFalse(context.containsBean("opsAlertConsumerFactory"));
            assertFalse(context.containsBean(OpsAlertKafkaConsumerConfig.DLT_TEMPLATE_BEAN_NAME));
            // The store is still present so the endpoint returns an (empty) list.
            assertTrue(context.containsBean("opsAlertStore"));
        });
    }
}
