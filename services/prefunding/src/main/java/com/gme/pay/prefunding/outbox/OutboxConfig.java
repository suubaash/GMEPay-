package com.gme.pay.prefunding.outbox;

import com.gme.pay.events.EventPublisher;
import com.gme.pay.events.LogEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring wiring for the Outbox pattern in prefunding (mirrors revenue-ledger's OutboxConfig).
 *
 * <ul>
 *   <li>{@link EnableScheduling} turns on {@code @Scheduled} so
 *       {@link OutboxPublisher#publishPending()} fires on its configured fixed delay
 *       ({@code gmepay.outbox.poll-ms}, default 1 s).</li>
 *   <li>A default {@link EventPublisher} bean ({@link LogEventPublisher}) is registered
 *       only if none is already present, so tests can swap in a
 *       {@code RecordingEventPublisher} and integration wiring can swap in the Kafka-backed
 *       publisher (lib-events-kafka) without touching this config.</li>
 * </ul>
 */
@Configuration
@EnableScheduling
public class OutboxConfig {

    @Bean
    @ConditionalOnMissingBean
    public EventPublisher logEventPublisher() {
        return new LogEventPublisher();
    }
}
