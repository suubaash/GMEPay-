package com.gme.pay.payment.alert;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the active {@link AlertSink} (gap T3-3). Deliberately the same two-bean shape as
 * ops-partner-bff's {@code PagingConfig} so the platform has one paging idiom, not two:
 *
 * <ul>
 *   <li>{@link WebhookAlertSink} when {@code gmepay.alert.sink.webhook-url} is set — a generic JSON
 *       POST to that URL (Slack / PagerDuty / Opsgenie / MS Teams / in-house), no vendor code and no
 *       embedded credential.</li>
 *   <li>{@link LogAlertSink} otherwise ({@code @ConditionalOnMissingBean}) — a log-only fallback so
 *       the alert path is functional with zero configuration.</li>
 * </ul>
 *
 * <p>Declaring both as {@code @Bean} methods (rather than {@code @Component} +
 * {@code @ConditionalOnMissingBean}) makes the fallback ordering deterministic: bean-method
 * conditions are evaluated after the webhook bean's {@code @ConditionalOnProperty}.
 *
 * <p><b>There is intentionally no default URL.</b> {@code @ConditionalOnProperty} matches on an empty
 * value, so shipping {@code gmepay.alert.sink.webhook-url=} would activate the webhook sink with a
 * blank target and make every delivery fail — worse than the log fallback. What to set is documented
 * in {@code Documentation/RUNBOOK_MONITORING.md}.
 */
@Configuration
public class AlertSinkConfig {

    @Bean
    @ConditionalOnProperty("gmepay.alert.sink.webhook-url")
    public AlertSink webhookAlertSink(
            @Value("${gmepay.alert.sink.webhook-url}") String webhookUrl,
            @Value("${gmepay.alert.sink.timeout-ms:3000}") long timeoutMs,
            @Value("${gmepay.alert.sink.max-attempts:3}") int maxAttempts) {
        return new WebhookAlertSink(webhookUrl, timeoutMs, maxAttempts);
    }

    @Bean
    @ConditionalOnMissingBean(AlertSink.class)
    public AlertSink logAlertSink() {
        return new LogAlertSink();
    }
}
