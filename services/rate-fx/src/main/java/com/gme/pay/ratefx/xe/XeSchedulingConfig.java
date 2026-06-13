package com.gme.pay.ratefx.xe;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Activates Spring's scheduling infrastructure only when the XE integration is enabled.
 * This keeps the default (disabled) boot path completely free of scheduler overhead
 * and prevents interference with existing tests.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "gmepay.rate-fx.xe.enabled", havingValue = "true")
public class XeSchedulingConfig {
}
