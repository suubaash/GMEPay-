package com.gme.pay.merchant.sync;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring's {@code @Scheduled} task executor when the merchant sync
 * feature is enabled ({@code gmepay.merchant-sync.enabled=true}).
 *
 * <p>Keeping {@link EnableScheduling} in a conditional {@link Configuration}
 * rather than on the application class means the scheduling infrastructure
 * (thread pool, task registry) is only initialised when the feature is actually
 * turned on — avoiding noise in unit-test contexts.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "gmepay.merchant-sync.enabled", havingValue = "true")
@EnableScheduling
public class MerchantSyncConfig {
    // No additional beans — sole purpose is to activate @EnableScheduling conditionally.
}
