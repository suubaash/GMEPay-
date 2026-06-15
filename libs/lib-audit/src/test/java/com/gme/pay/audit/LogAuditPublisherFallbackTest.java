package com.gme.pay.audit;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Verifies that {@link LogAuditPublisher} is the auto-configured fallback when no
 * {@link javax.sql.DataSource} bean is present in the context (i.e. no DB configured).
 */
class LogAuditPublisherFallbackTest {

    @Test
    void logAuditPublisherIsDefaultWhenNoDataSource() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.register(AuditPublisherAutoConfiguration.class);
            ctx.refresh();

            AuditPublisher publisher = ctx.getBean(AuditPublisher.class);
            assertInstanceOf(LogAuditPublisher.class, publisher,
                    "LogAuditPublisher must be the fallback when no DataSource is present");
        }
    }

    @Test
    void logAuditPublisherIsDefaultWhenKafkaNotConfigured() {
        // Neither datasource nor Kafka property — should get LogAuditPublisher.
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of()));
            ctx.register(AuditPublisherAutoConfiguration.class);
            ctx.refresh();

            AuditPublisher publisher = ctx.getBean(AuditPublisher.class);
            assertInstanceOf(LogAuditPublisher.class, publisher);
            assertTrue(ctx.getBeansOfType(DbAuditPublisher.class).isEmpty(),
                    "DbAuditPublisher must not be registered without a DataSource");
            assertTrue(ctx.getBeansOfType(KafkaAuditPublisher.class).isEmpty(),
                    "KafkaAuditPublisher must not be registered without bootstrap-servers");
        }
    }
}
