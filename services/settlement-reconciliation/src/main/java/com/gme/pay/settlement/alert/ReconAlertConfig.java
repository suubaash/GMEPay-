package com.gme.pay.settlement.alert;

import com.gme.pay.events.EventPublisher;
import com.gme.pay.events.kafka.KafkaEventPublisher;
import com.gme.pay.settlement.outbox.LoggingEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the ops-alert transport used by {@link ReconBreakAlerter}. Same selection rule as
 * {@link com.gme.pay.settlement.outbox.OutboxConfig}: the {@link KafkaEventPublisher} (topic
 * {@code gmepay.ops.alert}) when lib-events-kafka's auto-config is active
 * ({@code spring.kafka.bootstrap-servers} set), otherwise the {@link LoggingEventPublisher} fallback —
 * so local/test boots emit the alert to the log with no broker.
 *
 * <p>The bean is named {@link ReconBreakAlerter#ALERT_PUBLISHER_BEAN} so the alerter can select it by
 * qualifier without clashing with the by-name outbox appender (which must stay on the domain/outbox path).
 * Reconciliation-break alerts are operational signals, not transactional domain events, so they publish
 * directly through this transport rather than through the outbox.
 */
@Configuration
public class ReconAlertConfig {

    @Bean(ReconBreakAlerter.ALERT_PUBLISHER_BEAN)
    public EventPublisher opsAlertPublisher(ObjectProvider<KafkaEventPublisher> kafkaPublisher,
                                            LoggingEventPublisher loggingPublisher) {
        KafkaEventPublisher kafka = kafkaPublisher.getIfAvailable();
        return (kafka != null) ? kafka : loggingPublisher;
    }
}
