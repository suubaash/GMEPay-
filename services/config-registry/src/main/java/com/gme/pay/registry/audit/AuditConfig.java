package com.gme.pay.registry.audit;

import com.gme.pay.audit.AuditPublisher;
import com.gme.pay.audit.LogAuditPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default Spring wiring for the audit publisher.
 *
 * <p>Slice 1 ships only the in-process {@link LogAuditPublisher}: every event is
 * logged at INFO level, the hot {@code audit_log} table is the regulator-defensible
 * record, and there is no Kafka topic yet. Slice 8 hardening swaps in
 * {@code KafkaAuditPublisher} (which lives in {@code lib-audit}) by declaring its
 * own {@code @Bean AuditPublisher} that the {@link ConditionalOnMissingBean} below
 * defers to.
 */
@Configuration
public class AuditConfig {

    @Bean
    @ConditionalOnMissingBean(AuditPublisher.class)
    public AuditPublisher logAuditPublisher() {
        return new LogAuditPublisher();
    }
}
