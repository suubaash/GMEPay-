package com.gme.pay.ledger.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

/**
 * T3-11 defect 4, follow-up 3: <b>this factory reads {@code spring.kafka.listener.concurrency}.</b>
 *
 * <p>The defect was subtler than "the value was 1". The container factory is hand-built, and Boot
 * applies that property only to its own <em>auto-configured</em> factory — so the property was not
 * merely unset here, it was <b>unreadable</b>: an operator could set it, see it resolved in
 * {@code /actuator/env}, and change nothing. That is worse than a bad default, because it looks like a
 * lever.
 *
 * <p>Reading the concurrency off the built factory is the only way to tell those two apart, which is
 * why this test does that rather than asserting on configuration. Deliberately a copy of
 * notification-webhook's {@code WebhookKafkaConcurrencyTest}: four factories with the same defect
 * should be proved fixed by the same assertions.
 *
 * <p>The fleet-wide guard that stops a <em>fifth</em> bare factory appearing lives in
 * {@code libs/lib-events-kafka} ({@code KafkaListenerConcurrencyWiringGuardTest}).
 */
class RevenueLedgerKafkaConcurrencyTest {

    /**
     * Enough to activate the {@code @ConditionalOnProperty} gate. No broker is contacted: building a
     * consumer factory opens no connection — only starting a listener container does, and nothing
     * here starts one.
     */
    private ApplicationContextRunner runnerWithBroker() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(RevenueLedgerKafkaConsumerConfig.class)
                // The two @KafkaListener beans this configuration also declares need their handlers;
                // mocks keep this test about the container factory and nothing else.
                .withBean(PaymentApprovedEventHandler.class,
                        () -> Mockito.mock(PaymentApprovedEventHandler.class))
                .withBean(PaymentReversedEventHandler.class,
                        () -> Mockito.mock(PaymentReversedEventHandler.class))
                .withPropertyValues("spring.kafka.bootstrap-servers=localhost:9092");
    }

    @Test
    @DisplayName("concurrency comes from spring.kafka.listener.concurrency")
    void concurrencyIsReadFromConfiguration() {
        runnerWithBroker()
                .withPropertyValues("spring.kafka.listener.concurrency=5")
                .run(context -> assertThat(concurrencyOf(factory(context))).isEqualTo(5));
    }

    @Test
    @DisplayName("the shipped default is 3, matching the partition count in compose and the Helm ABI")
    void defaultMatchesThePartitionCount() {
        // Kafka assigns whole partitions, so threads beyond the partition count sit idle. 3 is not a
        // tuning guess: it is KAFKA_NUM_PARTITIONS in docker-compose.yml and
        // SPRING_KAFKA_LISTENER_CONCURRENCY in deploy/helm/gmepay/values.yaml. If one moves, this
        // fails and the other has to move with it.
        runnerWithBroker().run(context -> assertThat(concurrencyOf(factory(context))).isEqualTo(3));
    }

    @Test
    @DisplayName("a nonsensical value cannot silently stop revenue capture")
    void nonPositiveConcurrencyFallsBackToOne() {
        runnerWithBroker()
                .withPropertyValues("spring.kafka.listener.concurrency=0")
                // 0 would mean "no consumer threads": every payment.approved event would stop being
                // captured as revenue, silently, from a config typo. Clamp to the old behaviour.
                .run(context -> assertThat(concurrencyOf(factory(context))).isEqualTo(1));
    }

    private static ConcurrentKafkaListenerContainerFactory<?, ?> factory(
            org.springframework.context.ApplicationContext context) {
        return context.getBean(RevenueLedgerKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
                ConcurrentKafkaListenerContainerFactory.class);
    }

    /** {@code ConcurrentKafkaListenerContainerFactory#getConcurrency} is protected; read the field. */
    private static int concurrencyOf(ConcurrentKafkaListenerContainerFactory<?, ?> factory)
            throws Exception {
        var field = ConcurrentKafkaListenerContainerFactory.class.getDeclaredField("concurrency");
        field.setAccessible(true);
        Integer value = (Integer) field.get(factory);
        return value == null ? 1 : value;
    }
}
