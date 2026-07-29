package com.gme.pay.vault;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log =
            LoggerFactory.getLogger(InMemoryVaultAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(VaultClient.class)
    public InMemoryVaultClient inMemoryVaultClient(VaultProperties properties) {
        // T1-4: this fallback used to be silent, and the consequence was invisible —
        // docker-compose ran MinIO but never set GMEPAY_VAULT_ENDPOINT for
        // config-registry, so uploaded KYB documents (business registration, AOA,
        // UBO declaration, Wolfsberg CBDDQ) went to the heap and vanished on
        // restart while their partner_document rows survived, pointing at objects
        // that no longer existed. One WARN at startup makes that state observable.
        log.warn("DOCUMENT VAULT IS IN-MEMORY: gmepay.vault.endpoint is not set, so every"
                + " uploaded document is held on the heap and LOST ON RESTART (the metadata rows"
                + " that reference it are not). Acceptable for unit slices and throwaway dev"
                + " only. Set GMEPAY_VAULT_ENDPOINT (+ GMEPAY_VAULT_ACCESS_KEY /"
                + " GMEPAY_VAULT_SECRET_KEY) to use the S3/MinIO-backed vault. Bucket would be"
                + " '{}'.", properties.getBucket());
        return new InMemoryVaultClient(properties.getBucket());
    }
}
