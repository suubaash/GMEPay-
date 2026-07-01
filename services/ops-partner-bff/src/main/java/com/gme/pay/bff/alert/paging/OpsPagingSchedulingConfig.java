package com.gme.pay.bff.alert.paging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables Spring scheduling ONLY when escalation is turned on
 * ({@code gmepay.ops.paging.escalation.enabled=true}), so the platform default (escalation
 * off) never starts a scheduler thread. Paired with
 * {@link OpsPagingEscalationScheduler}'s matching {@code @ConditionalOnProperty}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "gmepay.ops.paging.escalation.enabled", havingValue = "true")
public class OpsPagingSchedulingConfig {
}
