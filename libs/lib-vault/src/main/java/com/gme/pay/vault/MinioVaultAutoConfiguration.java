package com.gme.pay.vault;

import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Production wiring of the ADR-006 document vault — active only when
 * {@code gmepay.vault.endpoint} is configured (e.g.
 * {@code GMEPAY_VAULT_ENDPOINT=http://minio:9000} in compose). Without the
 * property this whole class backs off and
 * {@link InMemoryVaultAutoConfiguration} provides the dev/test default.
 *
 * <p>All beans are {@code @ConditionalOnMissingBean} so a service (or test) can
 * override any single piece — most commonly the {@link MinioClient} — without
 * forking the rest of the wiring.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "gmepay.vault", name = "endpoint")
@EnableConfigurationProperties(VaultProperties.class)
public class MinioVaultAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public MinioClient gmepayVaultMinioClient(VaultProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(VaultClient.class)
    public MinioVaultClient minioVaultClient(MinioClient minioClient, VaultProperties properties) {
        return new MinioVaultClient(minioClient, properties.getBucket());
    }

    @Bean
    @ConditionalOnMissingBean
    public VaultBucketInitializer vaultBucketInitializer(MinioClient minioClient,
                                                         VaultProperties properties) {
        return new VaultBucketInitializer(minioClient, properties.getBucket(),
                properties.getRetentionYears());
    }
}
