package com.gme.pay.notify.config;

import com.gme.pay.notify.domain.WebhookHttpClient;
import com.gme.pay.notify.domain.WebhookSender;
import com.gme.pay.notify.signing.WebhookSigningService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * Wires the webhook dispatch pipeline: the domain {@link WebhookSender} bean and
 * {@code @Scheduled} support for {@code WebhookDispatcher}.
 *
 * <p>The {@link WebhookSender} is wired <b>without</b> a persistence collaborator —
 * the {@code WebhookDispatcher} owns the {@code webhook_delivery_log} row lifecycle
 * (it advances the originating PENDING row to DELIVERED/DLQ in place), so the sender
 * stays a pure sign-and-transport service and we avoid the duplicate-row insert that
 * the persistence-aware overload performs per attempt.
 *
 * <p>{@code @EnableScheduling} is harmless when the dispatcher is disabled (no
 * {@code @Scheduled} beans exist), so it can live here unconditionally.
 */
@Configuration
@EnableScheduling
public class WebhookDispatchConfig {

    @Bean
    public WebhookSender webhookSender(WebhookSigningService signingService,
                                       WebhookHttpClient httpClient,
                                       Clock clock) {
        return new WebhookSender(signingService, httpClient, clock);
    }
}
