package com.gme.pay.settlement.outbox;

import com.gme.pay.events.EventPublisher;
import com.gme.pay.events.kafka.KafkaEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wiring for the settlement Outbox. {@link EnableScheduling} drives {@link OutboxPublisher#publishPending()}
 * on its fixed delay. The drain transport is selected explicitly: the {@code @Primary}
 * {@link KafkaEventPublisher} when lib-events-kafka's auto-config is active
 * ({@code spring.kafka.bootstrap-servers} set), otherwise the {@link LoggingEventPublisher} fallback —
 * so local/test boots need no broker. The {@code ObjectProvider} guard keeps the module inert without Kafka.
 */
@Configuration
@EnableScheduling
public class OutboxConfig {

    @Bean
    public OutboxPublisher settlementOutboxPublisher(OutboxRepository repository,
                                                     ObjectProvider<KafkaEventPublisher> kafkaPublisher,
                                                     LoggingEventPublisher loggingPublisher) {
        KafkaEventPublisher kafka = kafkaPublisher.getIfAvailable();
        EventPublisher transport = (kafka != null) ? kafka : loggingPublisher;
        return new OutboxPublisher(repository, transport);
    }
}
