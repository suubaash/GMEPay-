package com.gme.pay.vault;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code gmepay.vault.*} configuration surface of lib-vault.
 *
 * <p>The presence of {@code gmepay.vault.endpoint} is the master switch: with it
 * set, {@link MinioVaultAutoConfiguration} wires the production
 * {@link MinioVaultClient}; without it, the heap-backed
 * {@link InMemoryVaultClient} is the default so dev boots and unit slices never
 * need MinIO ({@link InMemoryVaultAutoConfiguration}).
 *
 * <p>Default credentials match the docker-compose dev MinIO service
 * ({@code gmepay} / {@code gmepay-minio}); production always overrides via
 * {@code GMEPAY_VAULT_ACCESSKEY} / {@code GMEPAY_VAULT_SECRETKEY} env vars.
 */
@ConfigurationProperties(prefix = "gmepay.vault")
public class VaultProperties {

    /** MinIO S3 API endpoint, e.g. {@code http://localhost:9000}. Unset = in-memory vault. */
    private String endpoint;

    /** S3 access key. Defaults to the docker-compose dev credential. */
    private String accessKey = "gmepay";

    /** S3 secret key. Defaults to the docker-compose dev credential. */
    private String secretKey = "gmepay-minio";

    /** Vault bucket; {@code gmepay-partner-vault} per ADR-006. */
    private String bucket = MinioVaultClient.DEFAULT_BUCKET;

    /** Object-lock COMPLIANCE retention in years (ADR-006: 10). */
    private int retentionYears = 10;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public int getRetentionYears() {
        return retentionYears;
    }

    public void setRetentionYears(int retentionYears) {
        this.retentionYears = retentionYears;
    }
}
