package com.gme.pay.vault;

import io.minio.MinioClient;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(MinioVaultAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public MinioClient gmepayVaultMinioClient(VaultProperties properties) {
        // Region is always applied: the AWS Sig-V4 signer requires one, and it
        // is the single lever that differs between an S3 bucket region and the
        // MinIO/on-prem default. Endpoint + region keep the client provider-neutral.
        MinioClient client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .region(properties.getRegion())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();

        // The io.minio client selects the wire addressing style from the endpoint
        // host (AWS hosts -> virtual-host, everything else -> path-style); it has no
        // public path-style setter. Assert the declared GMEPAY_VAULT_PATH_STYLE
        // contract against that detection so a misconfigured endpoint surfaces at
        // boot rather than as a confusing 4xx on first PUT.
        boolean awsHost = isAwsHost(properties.getEndpoint());
        boolean detectedPathStyle = !awsHost;
        if (detectedPathStyle != properties.isPathStyle()) {
            log.warn("gmepay.vault.path-style={} but endpoint '{}' resolves to {} addressing. "
                            + "MinIO uses path-style for non-AWS hosts and virtual-host for AWS S3 hosts; "
                            + "set GMEPAY_VAULT_PATH_STYLE to match the endpoint provider.",
                    properties.isPathStyle(), properties.getEndpoint(),
                    detectedPathStyle ? "path-style" : "virtual-host");
        }
        log.info("vault S3 client: endpoint={} region={} path-style={}",
                properties.getEndpoint(), properties.getRegion(), detectedPathStyle);
        return client;
    }

    /** AWS S3 endpoints use virtual-host addressing; all others use path-style. */
    private static boolean isAwsHost(String endpoint) {
        if (endpoint == null) {
            return false;
        }
        String lower = endpoint.toLowerCase(Locale.ROOT);
        return lower.contains("amazonaws.com");
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
