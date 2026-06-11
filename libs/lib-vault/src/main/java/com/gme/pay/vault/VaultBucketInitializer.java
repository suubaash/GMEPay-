package com.gme.pay.vault;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetObjectLockConfigurationArgs;
import io.minio.messages.ObjectLockConfiguration;
import io.minio.messages.RetentionDurationYears;
import io.minio.messages.RetentionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Provisions the ADR-006 vault bucket at application start: creates
 * {@code gmepay-partner-vault} with <b>object-lock enabled</b> (which implies
 * versioning) and sets the default retention to <b>COMPLIANCE mode for 10
 * years</b> — not GOVERNANCE: even bucket admins cannot delete during
 * retention.
 *
 * <h2>Graceful degradation</h2>
 *
 * <p>MinIO being down at boot must NOT prevent the owning service from starting
 * (config-registry serves plenty of non-document traffic). {@link #ensureBucket()}
 * therefore catches every failure, logs a WARN, and reports {@code false};
 * document uploads will surface {@link VaultException} until MinIO returns, at
 * which point the next boot (or a manual re-run of this initializer) completes
 * the provisioning.
 *
 * <p>Note: object-lock can only be enabled at bucket CREATION. If the bucket
 * already exists without object-lock (e.g. hand-created via the console), the
 * retention call fails and the WARN tells the operator to recreate it — the
 * initializer never deletes/recreates a bucket on its own (it cannot, and must
 * not, destroy regulated storage).
 */
public class VaultBucketInitializer implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(VaultBucketInitializer.class);

    private final MinioClient minio;
    private final String bucket;
    private final int retentionYears;

    public VaultBucketInitializer(MinioClient minio, String bucket, int retentionYears) {
        this.minio = minio;
        this.bucket = bucket;
        this.retentionYears = retentionYears;
    }

    @Override
    public void afterSingletonsInstantiated() {
        ensureBucket();
    }

    /**
     * Idempotently create the bucket + enable object-lock retention.
     *
     * @return {@code true} when the bucket is fully provisioned; {@code false}
     *         when MinIO was unreachable or rejected the configuration (logged,
     *         never thrown).
     */
    public boolean ensureBucket() {
        try {
            boolean exists = minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                // objectLock(true) also enables versioning on the bucket.
                minio.makeBucket(MakeBucketArgs.builder()
                        .bucket(bucket)
                        .objectLock(true)
                        .build());
                log.info("created vault bucket '{}' with versioning + object-lock", bucket);
            }
            minio.setObjectLockConfiguration(SetObjectLockConfigurationArgs.builder()
                    .bucket(bucket)
                    .config(new ObjectLockConfiguration(
                            RetentionMode.COMPLIANCE,
                            new RetentionDurationYears(retentionYears)))
                    .build());
            log.info("vault bucket '{}' ready: COMPLIANCE retention {} years (ADR-006)",
                    bucket, retentionYears);
            return true;
        } catch (Exception e) {
            log.warn("vault bucket '{}' could not be provisioned — continuing without it "
                            + "(document uploads will fail until MinIO is reachable; if the bucket "
                            + "pre-exists WITHOUT object-lock it must be recreated): {}",
                    bucket, e.toString());
            return false;
        }
    }
}
