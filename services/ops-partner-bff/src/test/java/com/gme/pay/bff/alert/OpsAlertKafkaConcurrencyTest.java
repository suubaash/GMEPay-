package com.gme.pay.bff.alert;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;

/**
 * T3-11 defect 4, follow-up 3 — the <b>last</b> of the fleet's four hand-built Kafka container
 * factories: <b>this one reads {@code spring.kafka.listener.concurrency}.</b>
 *
 * <p>The defect was never "the value was 1". Boot binds that property onto its own
 * <em>auto-configured</em> container factory only; this factory is hand-built (MANUAL ack + a DLT
 * error handler), so the property was <b>unreadable</b> here — settable, visible in
 * {@code /actuator/env}, and inert. That is worse than a bad default: a lever that does nothing
 * survives review, survives an incident, and gets raised again next time.
 *
 * <p>Deliberately a copy of {@code PrefundingKafkaConcurrencyTest} /
 * {@code RevenueLedgerKafkaConcurrencyTest} rather than an improvement on them: four factories with
 * one defect should be pinned by one shape, or the next reader has to work out which variant is right.
 *
 * <p>The fleet-wide guard that stops a fifth bare factory appearing lives in
 * {@code libs/lib-events-kafka} ({@code KafkaListenerConcurrencyWiringGuardTest}); this service's
 * entry in its {@code KNOWN_UNFIXED} baseline was deleted together with the fix, which is what the
 * baseline's "can only ever shrink" property is for.
 */
class OpsAlertKafkaConcurrencyTest {

    /**
     * Enough to activate the {@code @ConditionalOnProperty("spring.kafka.bootstrap-servers")} gate. No
     * broker is contacted: building a consumer factory opens no connection — only starting a listener
     * container does, and nothing here starts one.
     */
    private ApplicationContextRunner runnerWithBroker() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
                .withUserConfiguration(OpsAlertKafkaConsumerConfig.class)
                .withBean(OpsAlertEventHandler.class, () -> Mockito.mock(OpsAlertEventHandler.class))
                .withBean(SettlementCompletedEventHandler.class,
                        () -> Mockito.mock(SettlementCompletedEventHandler.class))
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
        // 3 is not a tuning guess: it is KAFKA_NUM_PARTITIONS in docker-compose.yml and
        // SPRING_KAFKA_LISTENER_CONCURRENCY in deploy/helm/gmepay/values.yaml. Asserting it here is
        // what stops the two drifting apart silently.
        runnerWithBroker().run(context -> assertThat(concurrencyOf(factory(context))).isEqualTo(3));
    }

    @Test
    @DisplayName("a nonsensical value cannot silently stop ops alerts from being consumed")
    void nonPositiveConcurrencyFallsBackToOne() {
        runnerWithBroker()
                .withPropertyValues("spring.kafka.listener.concurrency=0")
                // 0 would mean "no consumer threads": alerts would stop being stored and stop being
                // paged on, from a config typo, with no error anywhere — silence on the one pipeline
                // whose entire purpose is that alerts are not silently dropped.
                .run(context -> assertThat(concurrencyOf(factory(context))).isEqualTo(1));
    }

    private static ConcurrentKafkaListenerContainerFactory<?, ?> factory(
            org.springframework.context.ApplicationContext context) {
        return context.getBean(OpsAlertKafkaConsumerConfig.LISTENER_CONTAINER_FACTORY,
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
