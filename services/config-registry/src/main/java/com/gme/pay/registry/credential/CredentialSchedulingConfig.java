package com.gme.pay.registry.credential;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled} processing for config-registry so the
 * {@link PartnerCredentialRotationScheduler} cron fires — the same pattern as
 * prefunding's / revenue-ledger's {@code OutboxConfig} ({@code @EnableScheduling}
 * on a feature-local config class rather than the application class, so unit
 * slices that never import this package never start the scheduler thread).
 */
@Configuration
@EnableScheduling
public class CredentialSchedulingConfig {
}
