package com.gme.pay.notify.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

/**
 * T3-11 defect 4: <b>Kafka consumer concurrency reads from configuration.</b>
 *
 * <p>The defect was subtler than "the value was 1". The container factory is hand-built, and Boot's
 * {@code spring.kafka.listener.concurrency} property is only applied by Boot's <em>auto-configured</em>
 * factory — so the property was not merely unset, it was <b>unreadable</b>: an operator could set it,
 * see it resolved in {@code /actuator/env}, and change nothing at all. That is worse than a bad
 * default, because it looks like a lever.
 *
 * <p>These tests read the concurrency off the built factory, which is the only way to tell the two
 * apart.
 */
class WebhookKafkaConcurrencyTest {

    /**
     * Enough to activate the {@code @ConditionalOnProperty} gate; no broker is contacted, because
     * building a consumer factory does not open a connection — only starting a listener container
     * does, and nothing here starts one.
     */
    private ApplicationContextRunner runnerWithBroker() {
        return new ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        org.springframework.boot.autoconfigure.context
                                .PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(WebhookKafkaConsumerConfig.class)
                // The two @KafkaListener beans this configuration also declares need their handlers;
                // mocks keep this test about the container factory and nothing else.
                .withBean(com.gme.pay.notify.consumer.PaymentApprovedEventHandler.class,
                        () -> org.mockito.Mockito.mock(
                                com.gme.pay.notify.consumer.PaymentApprovedEventHandler.class))
                .withBean(com.gme.pay.notify.consumer.PaymentReversedEventHandler.class,
                        () -> org.mockito.Mockito.mock(
                                com.gme.pay.notify.consumer.PaymentReversedEventHandler.class))
                .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092");
    }

    @Test
    @DisplayName("concurrency comes from spring.kafka.listener.concurrency")
    void concurrencyIsReadFromConfiguration() {
        runnerWithBroker()
                .withPropertyValues("spring.kafka.listener.concurrency=5")
                .run(context -> {
                    ConcurrentKafkaListenerContainerFactory<?, ?> factory = context.getBean(
                            WebhookKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
                            ConcurrentKafkaListenerContainerFactory.class);
                    assertThat(factory.getContainerProperties()).isNotNull();
                    assertThat(concurrencyOf(factory)).isEqualTo(5);
                });
    }

    @Test
    @DisplayName("the shipped default is 3, matching the partition count set in compose and the Helm ABI")
    void defaultMatchesThePartitionCount() {
        runnerWithBroker().run(context -> {
            ConcurrentKafkaListenerContainerFactory<?, ?> factory = context.getBean(
                    WebhookKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
                    ConcurrentKafkaListenerContainerFactory.class);
            // Kafka assigns whole partitions, so threads beyond the partition count sit idle. 3 is
            // not a tuning guess: it is the KAFKA_NUM_PARTITIONS in docker-compose.yml and the
            // SPRING_KAFKA_LISTENER_CONCURRENCY in deploy/helm/gmepay/values.yaml. If one moves, this
            // fails and the other has to move with it.
            assertThat(concurrencyOf(factory)).isEqualTo(3);
        });
    }

    @Test
    @DisplayName("a nonsensical value cannot silently disable the listener")
    void nonPositiveConcurrencyFallsBackToOne() {
        runnerWithBroker()
                .withPropertyValues("spring.kafka.listener.concurrency=0")
                .run(context -> {
                    ConcurrentKafkaListenerContainerFactory<?, ?> factory = context.getBean(
                            WebhookKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
                            ConcurrentKafkaListenerContainerFactory.class);
                    // 0 would mean "no consumer threads" — every webhook would stop being produced,
                    // silently, from a config typo. Clamp to the previous behaviour instead.
                    assertThat(concurrencyOf(factory)).isEqualTo(1);
                });
    }

    /** {@code ConcurrentKafkaListenerContainerFactory#getConcurrency} is protected; read the field. */
    private static int concurrencyOf(ConcurrentKafkaListenerContainerFactory<?, ?> factory) throws Exception {
        var field = ConcurrentKafkaListenerContainerFactory.class.getDeclaredField("concurrency");
        field.setAccessible(true);
        Integer value = (Integer) field.get(factory);
        return value == null ? 1 : value;
    }
}
