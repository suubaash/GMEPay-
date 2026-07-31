package com.gme.pay.vault;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.GetObjectTagsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.SetObjectTagsArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import io.minio.messages.Item;
import io.minio.messages.Tags;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import okhttp3.Headers;

/**
 * In-process S3 stand-in: a real {@link MinioClient} subclass whose S3 verbs are
 * served from a concurrent map instead of the network.
 *
 * <h2>Why not Testcontainers</h2>
 *
 * <p>{@link MinioVaultClientIT} already exercises a real MinIO, but it is
 * {@code @Tag("docker")} and excluded from {@code :libs:lib-vault:test}. The
 * defect being pinned here is a <b>race</b>, and a race test has to be able to
 * hold two writers at a chosen point and release them together — which is
 * exactly what a real server will not let you do. This fake keeps the parts that
 * matter for the fix faithful:
 *
 * <ul>
 *   <li><b>Object versions.</b> Every PUT to an existing key appends a version
 *       with a fresh version id (the vault bucket is created with object-lock,
 *       which implies versioning), and {@code listObjects(includeVersions)}
 *       returns all of them — the signal
 *       {@code MinioVaultClient.verifySoleClaimant} reads.</li>
 *   <li><b>Conditional writes, both ways.</b> {@link #honorIfNoneMatch} switches
 *       between a server that enforces {@code If-None-Match: *} with a
 *       {@code 412 PreconditionFailed} (AWS S3 since Aug 2024, recent MinIO) and
 *       one that silently ignores the header (the MinIO release pinned in
 *       {@code docker-compose.yml}). The fix must be safe on both.</li>
 *   <li><b>Real bytes.</b> PUT drains the caller's stream, so the production
 *       {@code DigestInputStream} SHA-256 is computed for real, and GET/STAT
 *       serve what was written.</li>
 * </ul>
 *
 * <p>What it is NOT: an S3 conformance implementation. No multipart, no
 * pagination, no retention/legal-hold enforcement, no delete (the port has none).
 */
final class FakeS3MinioClient extends MinioClient {

    /** One immutable object version. */
    record StoredVersion(String versionId, byte[] bytes, String contentType,
                         Map<String, String> userMetadata) {
    }

    /** MinIO parses {@code Last-Modified} with a named zone, so emit GMT, not "Z". */
    private static final ZoneId GMT = ZoneId.of("GMT");

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);

    /** key -> versions, oldest first. The last element is "latest". */
    private final ConcurrentMap<String, List<StoredVersion>> objects = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Map<String, String>> tags = new ConcurrentHashMap<>();

    /** Every object key ever PUT, in order — lets a test see a silent overwrite. */
    final List<String> putKeys = new CopyOnWriteArrayList<>();

    private final boolean honorIfNoneMatch;

    /**
     * Tripped inside every plain (non-versioned) {@code listObjects} — i.e. the
     * "what is the highest version?" read at the top of
     * {@code MinioVaultClient.store}. Parking both writers there and releasing
     * them together is what makes the race deterministic instead of hopeful.
     */
    private volatile CyclicBarrier readBarrier;

    FakeS3MinioClient(boolean honorIfNoneMatch) {
        super(MinioClient.builder()
                .endpoint("http://127.0.0.1:9")
                .region("us-east-1")
                .credentials("fake-access-key", "fake-secret-key")
                .build());
        this.honorIfNoneMatch = honorIfNoneMatch;
    }

    void parkPlainListingsOn(CyclicBarrier barrier) {
        this.readBarrier = barrier;
    }

    /** Seed a key as if written by an older client (no version ledger). */
    void seedLegacyObject(String key, String body) {
        objects.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>())
                .add(new StoredVersion(UUID.randomUUID().toString(),
                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "application/octet-stream", Map.of()));
    }

    /** Live count of object versions at a key (0 when absent). */
    int versionCount(String key) {
        List<StoredVersion> versions = objects.get(key);
        return versions == null ? 0 : versions.size();
    }

    List<String> keys() {
        return new ArrayList<>(objects.keySet());
    }

    // ------------------------------------------------------------------ verbs

    @Override
    public ObjectWriteResponse putObject(PutObjectArgs args) throws ErrorResponseException {
        String key = args.object();
        byte[] body = drain(args.stream(), key);
        putKeys.add(key);

        String versionId = UUID.randomUUID().toString();
        Map<String, String> meta = new LinkedHashMap<>();
        if (args.userMetadata() != null) {
            args.userMetadata().entries().forEach(e -> meta.put(stripMetaPrefix(e.getKey()),
                    e.getValue()));
        }
        String contentType;
        try {
            contentType = args.contentType();
        } catch (IOException e) {
            contentType = "application/octet-stream";
        }
        StoredVersion version = new StoredVersion(versionId, body, contentType, meta);

        if (honorIfNoneMatch && hasIfNoneMatchStar(args)) {
            // A server that enforces the precondition does so ATOMICALLY — a
            // check-then-put here would reintroduce, inside the test double, the
            // very read-modify-write the fix removes, and would make the honoring
            // case indistinguishable from the ignoring one.
            List<StoredVersion> created = new CopyOnWriteArrayList<>();
            created.add(version);
            if (objects.putIfAbsent(key, created) != null) {
                throw error("PreconditionFailed",
                        "At least one of the pre-conditions you specified did not hold", key);
            }
        } else {
            // No enforcement: both racing writers land a version on the same key,
            // which is exactly what an older MinIO does with If-None-Match.
            objects.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(version);
        }
        return new ObjectWriteResponse(Headers.of(), "bucket", "us-east-1", key,
                "\"" + versionId + "\"", versionId);
    }

    @Override
    public Iterable<Result<Item>> listObjects(ListObjectsArgs args) {
        if (!args.includeVersions()) {
            awaitBarrier();
        }
        String prefix = args.prefix() == null ? "" : args.prefix();
        List<Result<Item>> results = new ArrayList<>();
        for (Map.Entry<String, List<StoredVersion>> entry : objects.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix)) {
                continue;
            }
            List<StoredVersion> versions = entry.getValue();
            if (args.includeVersions()) {
                for (int i = 0; i < versions.size(); i++) {
                    results.add(new Result<>(item(key, versions.get(i).versionId(),
                            i == versions.size() - 1)));
                }
            } else {
                results.add(new Result<>(item(key,
                        versions.get(versions.size() - 1).versionId(), true)));
            }
        }
        return results;
    }

    @Override
    public StatObjectResponse statObject(StatObjectArgs args) throws ErrorResponseException {
        String key = args.object();
        StoredVersion latest = latest(key);
        Headers.Builder headers = new Headers.Builder()
                .add("Content-Length", String.valueOf(latest.bytes().length))
                .add("Content-Type", latest.contentType())
                .add("ETag", "\"" + latest.versionId() + "\"")
                .add("Last-Modified", ZonedDateTime.now(GMT).format(HTTP_DATE))
                .add("x-amz-version-id", latest.versionId());
        latest.userMetadata().forEach((k, v) -> headers.add("x-amz-meta-" + k, v));
        return new StatObjectResponse(headers.build(), "bucket", "us-east-1", key);
    }

    @Override
    public GetObjectResponse getObject(GetObjectArgs args) throws ErrorResponseException {
        String key = args.object();
        StoredVersion latest = latest(key);
        return new GetObjectResponse(Headers.of(), "bucket", "us-east-1", key,
                new ByteArrayInputStream(latest.bytes()));
    }

    @Override
    public void setObjectTags(SetObjectTagsArgs args) {
        tags.put(args.object(), args.tags().get());
    }

    @Override
    public Tags getObjectTags(GetObjectTagsArgs args) {
        return Tags.newObjectTags(tags.getOrDefault(args.object(), Map.of()));
    }

    // ---------------------------------------------------------------- helpers

    private StoredVersion latest(String key) throws ErrorResponseException {
        List<StoredVersion> versions = objects.get(key);
        if (versions == null || versions.isEmpty()) {
            throw error("NoSuchKey", "The specified key does not exist.", key);
        }
        return versions.get(versions.size() - 1);
    }

    private void awaitBarrier() {
        CyclicBarrier barrier = this.readBarrier;
        if (barrier == null) {
            return;
        }
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("race barrier did not trip", e);
        }
    }

    private static byte[] drain(InputStream in, String key) {
        try {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("fake S3 could not read the body of " + key, e);
        }
    }

    private static boolean hasIfNoneMatchStar(PutObjectArgs args) {
        if (args.extraHeaders() == null) {
            return false;
        }
        return args.extraHeaders().entries().stream()
                .anyMatch(e -> "if-none-match".equalsIgnoreCase(e.getKey())
                        && "*".equals(e.getValue()));
    }

    private static String stripMetaPrefix(String headerKey) {
        String lower = headerKey.toLowerCase(Locale.ROOT);
        return lower.startsWith("x-amz-meta-") ? headerKey.substring("x-amz-meta-".length())
                : headerKey;
    }

    private static Item item(String key, String versionId, boolean latest) {
        return new Item(key) {
            @Override
            public String objectName() {
                return key;
            }

            @Override
            public String versionId() {
                return versionId;
            }

            @Override
            public boolean isLatest() {
                return latest;
            }

            @Override
            public boolean isDeleteMarker() {
                return false;
            }
        };
    }

    private static ErrorResponseException error(String code, String message, String key) {
        return new ErrorResponseException(
                new ErrorResponse(code, message, "bucket", key, key, "req", "host"), null, null);
    }
}
