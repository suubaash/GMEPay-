package com.gme.pay.scheme.zeropay.ops;

import com.gme.pay.events.EventPublisher;
import com.gme.pay.events.kafka.KafkaEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the ops-alert transport for the ZeroPay adapter (gap <b>T3-4</b>).
 *
 * <p>Same selection rule as settlement-reconciliation's {@code ReconAlertConfig}: the
 * {@link KafkaEventPublisher} (topic {@code gmepay.ops.alert}) when lib-events-kafka's auto-config is active
 * ({@code spring.kafka.bootstrap-servers} set), otherwise {@link ZpLoggingEventPublisher}. Selecting through
 * an {@link ObjectProvider} rather than {@code @ConditionalOnProperty} keeps the decision in one readable
 * bean and avoids the present-but-blank-property trap noted in the T3-3 fix report.
 *
 * <p>Named so {@link ZpBatchRunAlerter} can select it by qualifier. This adapter publishes no domain events
 * of its own, so there is no outbox path for this bean to collide with.
 */
@Configuration
public class ZpOpsAlertConfig {

    /** Bean name for the ops-alert transport. */
    public static final String ALERT_PUBLISHER_BEAN = "zpOpsAlertPublisher";

    @Bean(ALERT_PUBLISHER_BEAN)
    public EventPublisher zpOpsAlertPublisher(ObjectProvider<KafkaEventPublisher> kafkaPublisher,
                                              ZpLoggingEventPublisher loggingPublisher) {
        KafkaEventPublisher kafka = kafkaPublisher.getIfAvailable();
        return (kafka != null) ? kafka : loggingPublisher;
    }
}
