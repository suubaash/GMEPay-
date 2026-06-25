package com.gme.pay.payment.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} support for payment-executor (currently the
 * {@link com.gme.pay.payment.sweeper.AuthorizationExpirySweeper}).
 *
 * <p>{@code @EnableScheduling} is harmless when no {@code @Scheduled} bean is active — the sweeper is
 * {@code @ConditionalOnProperty}, so with it disabled this just wires the (idle) scheduling
 * infrastructure. Lives here unconditionally, mirroring notification-webhook's WebhookDispatchConfig.
 */
@Configuration
@EnableScheduling
public class PaymentSchedulingConfig {
}
