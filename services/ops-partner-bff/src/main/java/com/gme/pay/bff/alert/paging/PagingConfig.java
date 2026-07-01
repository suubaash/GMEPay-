package com.gme.pay.bff.alert.paging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the active {@link PagingPort} adapter (ADR-015 — vendor-agnostic, no cloud SDK):
 *
 * <ul>
 *   <li>{@link WebhookPagingAdapter} when {@code gmepay.ops.paging.webhook-url} is set —
 *       POSTs the alert as JSON to the configured on-call webhook (Slack / PagerDuty /
 *       Opsgenie / MS Teams).</li>
 *   <li>{@link LogPagingAdapter} otherwise ({@code @ConditionalOnMissingBean}) — a log-only
 *       fallback so the paging path is functional with zero configuration.</li>
 * </ul>
 *
 * <p>Declaring both as {@code @Bean} methods (rather than {@code @Component} +
 * {@code @ConditionalOnMissingBean}) makes the fallback ordering deterministic: bean-method
 * conditions are evaluated after the webhook bean's {@code @ConditionalOnProperty}.
 */
@Configuration
public class PagingConfig {

    @Bean
    @ConditionalOnProperty("gmepay.ops.paging.webhook-url")
    public PagingPort webhookPagingAdapter(
            @Value("${gmepay.ops.paging.webhook-url}") String webhookUrl,
            @Value("${gmepay.ops.paging.timeout-ms:3000}") long timeoutMs,
            @Value("${gmepay.ops.paging.max-attempts:3}") int maxAttempts) {
        return new WebhookPagingAdapter(webhookUrl, timeoutMs, maxAttempts);
    }

    @Bean
    @ConditionalOnMissingBean(PagingPort.class)
    public PagingPort logPagingAdapter() {
        return new LogPagingAdapter();
    }
}
