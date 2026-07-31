package com.gme.pay.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.messages.ObjectLockConfiguration;
import io.minio.messages.RetentionMode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Docker-backed integration test of {@link MinioVaultClient} +
 * {@link VaultBucketInitializer} against a real MinIO (Testcontainers).
 *
 * <p>Runs only via {@code :libs:lib-vault:integrationTest} on CI runners with a
 * Docker engine — locally it compiles but is excluded by the root
 * {@code test { excludeTags 'docker' } } convention and additionally disabled
 * without Docker.
 *
 * <p>Pins the production guarantees ADR-006 cares about:
 * <ol>
 *   <li>the initializer provisions the bucket with object-lock COMPLIANCE
 *       retention (and is idempotent);</li>
 *   <li>store → retrieve round-trips bytes + metadata + SHA-256;</li>
 *   <li>re-storing the same {@code (partnerCode, docType)} mints v2 next to an
 *       immutable v1 (no overwrite);</li>
 *   <li>concurrent stores of the same {@code (partnerCode, docType)} never both
 *       claim a version — against whatever conditional-write support the pinned
 *       MinIO image actually has, which is the one thing
 *       {@code MinioVaultClientVersionRaceTest}'s in-process fake can only
 *       simulate.</li>
 * </ol>
 */
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
class MinioVaultClientIT {

    private static final String BUCKET = "gmepay-partner-vault";

    @Container
    private static final MinIOContainer MINIO =
            new MinIOContainer("minio/minio:RELEASE.2024-08-03T04-33-23Z")
                    .withUserName("gmepay")
                    .withPassword("gmepay-minio");

    private static MinioClient minioClient;
    private static MinioVaultClient vault;

    @BeforeAll
    static void setUp() {
        minioClient = MinioClient.builder()
                .endpoint(MINIO.getS3URL())
                .credentials(MINIO.getUserName(), MINIO.getPassword())
                .build();
        VaultBucketInitializer initializer = new VaultBucketInitializer(minioClient, BUCKET, 10);
        assertThat(initializer.ensureBucket()).isTrue();
        // Idempotent: a second boot must not fail on the existing bucket.
        assertThat(initializer.ensureBucket()).isTrue();
        vault = new MinioVaultClient(minioClient, BUCKET);
    }

    private static InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void initializer_provisionsObjectLockComplianceBucket() throws Exception {
        assertThat(minioClient.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build()))
                .isTrue();
        ObjectLockConfiguration lock = minioClient.getObjectLockConfiguration(
                io.minio.GetObjectLockConfigurationArgs.builder().bucket(BUCKET).build());
        assertThat(lock.mode()).isEqualTo(RetentionMode.COMPLIANCE);
        assertThat(lock.duration().duration()).isEqualTo(10);
    }

    @Test
    void storeThenRetrieve_roundTripsThroughRealMinio() throws Exception {
        String body = "BOARD-RESOLUTION-PDF-BYTES";
        String expectedSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));

        VaultObjectRef ref = vault.store("GMEREMIT", "BOARD_RESOLUTION",
                "이사회결의서-2026.pdf", "application/pdf", stream(body));

        assertThat(ref.uri()).startsWith("s3://" + BUCKET + "/GMEREMIT/BOARD_RESOLUTION/");
        assertThat(ref.version()).isEqualTo(1);
        assertThat(ref.sha256()).isEqualTo(expectedSha);

        VaultObject retrieved = vault.retrieve(ref.uri());
        try (InputStream in = retrieved.content()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo(body);
        }
        // Korean filename survives the URL-encoded user-metadata round trip.
        assertThat(retrieved.filename()).isEqualTo("이사회결의서-2026.pdf");
        assertThat(retrieved.contentType()).isEqualTo("application/pdf");
        assertThat(retrieved.size()).isEqualTo(body.length());
        assertThat(retrieved.sha256()).isEqualTo(expectedSha);
    }

    @Test
    void reStore_mintsNextVersion_priorStaysRetrievable() throws Exception {
        VaultObjectRef v1 = vault.store("VERSIONCO", "LICENSE", "lic.pdf",
                "application/pdf", stream("license v1"));
        VaultObjectRef v2 = vault.store("VERSIONCO", "LICENSE", "lic.pdf",
                "application/pdf", stream("license v2 — renewed"));

        assertThat(v1.version()).isEqualTo(1);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.uri()).isNotEqualTo(v1.uri());

        try (InputStream in = vault.retrieve(v1.uri()).content()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .as("v1 must remain immutable next to v2")
                    .isEqualTo("license v1");
        }
        try (InputStream in = vault.retrieve(v2.uri()).content()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("license v2 — renewed");
        }
    }

    /**
     * The defect this pins: version used to be "count the prefix, add one", so
     * two concurrent uploads both minted {@code vN} — different object keys (the
     * {@code docId} is a per-call UUID, so no bytes were lost) but the SAME
     * version label on two immutable, undeletable KYB documents.
     *
     * <p>Asserted against real MinIO, because whether the endpoint enforces
     * {@code If-None-Match: *} decides which half of the fix does the work:
     * enforced → one winner and N-1 clean {@code 412}s; ignored → the post-PUT
     * object-version listing catches the double claim and everybody fails
     * closed. Both are acceptable; two winners at one version is not.
     */
    @Test
    void concurrentStores_neverMintTheSameVersionTwice() throws Exception {
        int writers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        List<VaultObjectRef> won = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> lost = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String body = "CONCURRENT-UPLOAD-" + i;
                futures.add(pool.submit(() -> {
                    try {
                        go.await(10, TimeUnit.SECONDS);
                        won.add(vault.store("RACECO", "CBDDQ", "cbddq.pdf", "application/pdf",
                                stream(body)));
                    } catch (Exception e) {
                        lost.add(e);
                    }
                }));
            }
            go.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(won).as("at least one writer must get through").isNotEmpty();
        assertThat(won).extracting(VaultObjectRef::version)
                .as("two documents may never share a version")
                .doesNotHaveDuplicates();
        assertThat(lost).allSatisfy(e -> assertThat(e)
                .as("a loser must be told, in the port's own vocabulary")
                .isInstanceOf(VaultVersionConflictException.class));
        assertThat(won.size() + lost.size()).isEqualTo(writers);

        // Every winner's bytes are intact — nothing was overwritten.
        for (VaultObjectRef ref : won) {
            try (InputStream in = vault.retrieve(ref.uri()).content()) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                        .startsWith("CONCURRENT-UPLOAD-");
            }
        }
    }

    @Test
    void retrieve_unknownKey_throwsVaultException() {
        assertThatThrownBy(() -> vault.retrieve("s3://" + BUCKET + "/GHOST/LICENSE/none/v1.pdf"))
                .isInstanceOf(VaultException.class);
    }
}
