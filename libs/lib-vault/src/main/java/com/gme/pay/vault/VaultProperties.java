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
 * {@code GMEPAY_VAULT_ACCESS_KEY} / {@code GMEPAY_VAULT_SECRET_KEY} env vars.
 *
 * <p><b>Cloud-agnostic contract.</b> Every backing value is injected, never
 * baked. The same {@code io.minio} S3-API client works against any S3-compatible
 * endpoint — self-hosted MinIO (on-prem default), AWS S3, or an Azure-fronting
 * S3 gateway — by varying only these properties / env vars:
 * <ul>
 *   <li>{@code GMEPAY_VAULT_ENDPOINT} — S3 API URL (master switch).</li>
 *   <li>{@code GMEPAY_VAULT_REGION} — bucket region; {@code us-east-1} default
 *       is correct for MinIO and a safe S3 default.</li>
 *   <li>{@code GMEPAY_VAULT_PATH_STYLE} — {@code true} (default) = path-style
 *       addressing (MinIO / on-prem); {@code false} = virtual-host addressing
 *       (AWS S3). The MinIO client auto-selects the wire style from the endpoint
 *       host (AWS hosts → virtual-host, all others → path-style); this flag is
 *       the declared contract and is asserted against that detection at boot.</li>
 *   <li>{@code GMEPAY_VAULT_ACCESS_KEY} / {@code GMEPAY_VAULT_SECRET_KEY}.</li>
 * </ul>
 * No cloud-provider SDK is on the classpath — only the open S3 API.
 */
@ConfigurationProperties(prefix = "gmepay.vault")
public class VaultProperties {

    /** MinIO S3 API endpoint, e.g. {@code http://localhost:9000}. Unset = in-memory vault. */
    private String endpoint;

    /**
     * S3 region. On-prem MinIO ignores it but the S3 signer requires a value;
     * {@code us-east-1} is MinIO's default and a safe AWS/Azure-gateway default.
     * Override via {@code GMEPAY_VAULT_REGION} for an AWS/S3 bucket's real region.
     */
    private String region = "us-east-1";

    /**
     * S3 addressing style. {@code true} (default) = path-style
     * ({@code endpoint/bucket/key}) for MinIO / on-prem and most S3 gateways;
     * {@code false} = virtual-host style ({@code bucket.endpoint/key}) for AWS S3.
     * Override via {@code GMEPAY_VAULT_PATH_STYLE}.
     */
    private boolean pathStyle = true;

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

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public boolean isPathStyle() {
        return pathStyle;
    }

    public void setPathStyle(boolean pathStyle) {
        this.pathStyle = pathStyle;
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
