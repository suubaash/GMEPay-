package com.gme.pay.txn.outbox;

import com.gme.pay.events.EventPublisher;
import com.gme.pay.events.kafka.KafkaEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring wiring for the Outbox pattern in transaction-mgmt.
 *
 * <ul>
 *   <li>{@link EnableScheduling} turns on {@code @Scheduled} so
 *       {@link OutboxPublisher#publishPending()} fires on its fixed delay
 *       ({@code gmepay.outbox.poll-ms}, default 1 s).</li>
 *   <li>The drain's transport is selected here explicitly: the {@code @Primary}
 *       {@link KafkaEventPublisher} when lib-events-kafka's auto-configuration is active
 *       ({@code spring.kafka.bootstrap-servers} set — docker-compose exports
 *       {@code SPRING_KAFKA_BOOTSTRAP_SERVERS}), otherwise the local
 *       {@link LoggingEventPublisher} fallback. Explicit selection keeps the drain from
 *       ever being wired to {@link OutboxAppender} (which would loop events back into the
 *       outbox instead of shipping them).</li>
 * </ul>
 */
@Configuration
@EnableScheduling
public class OutboxConfig {

    @Bean
    public OutboxPublisher outboxPublisher(OutboxRepository repository,
                                           ObjectProvider<KafkaEventPublisher> kafkaPublisher,
                                           LoggingEventPublisher loggingPublisher) {
        KafkaEventPublisher kafka = kafkaPublisher.getIfAvailable();
        EventPublisher transport = (kafka != null) ? kafka : loggingPublisher;
        return new OutboxPublisher(repository, transport);
    }
}
