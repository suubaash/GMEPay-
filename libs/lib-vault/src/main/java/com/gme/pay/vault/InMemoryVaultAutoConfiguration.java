package com.gme.pay.vault;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Dev/test fallback wiring of the document vault: when nothing else provided a
 * {@link VaultClient} — i.e. {@code gmepay.vault.endpoint} is unset so
 * {@link MinioVaultAutoConfiguration} backed off — a heap-backed
 * {@link InMemoryVaultClient} is registered so services depending on the vault
 * boot with zero infrastructure.
 *
 * <p>Ordered {@code after} the MinIO auto-configuration so the
 * {@code @ConditionalOnMissingBean} check observes the production bean when the
 * endpoint property is present.
 */
@AutoConfiguration(after = MinioVaultAutoConfiguration.class)
@EnableConfigurationProperties(VaultProperties.class)
public class InMemoryVaultAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(VaultClient.class)
    public InMemoryVaultClient inMemoryVaultClient(VaultProperties properties) {
        return new InMemoryVaultClient(properties.getBucket());
    }
}
