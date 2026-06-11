package com.gme.pay.vault;

import io.minio.GetObjectArgs;
import io.minio.GetObjectTagsArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.Result;
import io.minio.SetObjectTagsArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.messages.Item;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link VaultClient} backed by MinIO per ADR-006: bucket
 * {@code gmepay-partner-vault}, object keys
 * {@code <partnerCode>/<docType>/<docId>/v<n>[.<ext>]}, SHA-256 computed while
 * streaming (no full buffering), original filename + digest carried as S3 user
 * metadata. Bucket-level versioning + object-lock are provisioned by
 * {@link VaultBucketInitializer}, so every PUT lands as an immutable,
 * compliance-retained object.
 *
 * <p>TODO(ADR-006/R3): server-side encryption with per-partner keys from
 * HashiCorp Vault (SSE-C / SSE-KMS headers on {@link PutObjectArgs}). Objects
 * are stored plain until the Vault deployment lands in R3; the call site below
 * is the single place the SSE header wiring will go, which is what enables the
 * PIPA Art. 21 crypto-shred on partner offboarding.
 */
public class MinioVaultClient implements VaultClient {

    /** Production bucket name per ADR-006. */
    public static final String DEFAULT_BUCKET = "gmepay-partner-vault";

    /** S3 multipart part size for unknown-length streams (MinIO minimum is 5 MiB). */
    private static final long PART_SIZE = 10L * 1024 * 1024;

    /** User-metadata key carrying the URL-encoded original filename. */
    static final String META_FILENAME = "filename";

    /**
     * Object-TAG key carrying the lowercase hex SHA-256 of the bytes. A tag —
     * not user metadata — because the digest is only known after the PUT has
     * consumed the stream, and tags remain writable on object-locked objects
     * while metadata would require a (forbidden) rewrite.
     */
    static final String TAG_SHA256 = "sha256";

    private static final Logger log = LoggerFactory.getLogger(MinioVaultClient.class);

    private final MinioClient minio;
    private final String bucket;

    public MinioVaultClient(MinioClient minio, String bucket) {
        this.minio = minio;
        this.bucket = bucket == null || bucket.isBlank() ? DEFAULT_BUCKET : bucket;
    }

    @Override
    public VaultObjectRef store(String partnerCode, String docType, String filename,
                                String contentType, InputStream content) {
        requireToken("partnerCode", partnerCode);
        requireToken("docType", docType);
        if (filename == null || filename.isBlank()) {
            throw new VaultException("filename is required");
        }
        String resolvedContentType = contentType == null || contentType.isBlank()
                ? "application/octet-stream" : contentType;

        String prefix = partnerCode + "/" + docType + "/";
        int version = countObjects(prefix) + 1;
        String key = prefix + UUID.randomUUID() + "/v" + version
                + InMemoryVaultClient.extensionOf(filename);

        MessageDigest digest = sha256Digest();
        try {
            // S3 user metadata must be US-ASCII header-safe; the original
            // filename may be Korean — URL-encode it and decode on retrieve.
            Map<String, String> userMetadata = new LinkedHashMap<>();
            userMetadata.put(META_FILENAME, URLEncoder.encode(filename, StandardCharsets.UTF_8));

            // SHA-256 is computed WHILE streaming to MinIO (DigestInputStream),
            // so large documents are never buffered in heap. The digest is only
            // final after putObject has consumed the stream, which is why it is
            // patched onto the ref below rather than into the user metadata —
            // a follow-up stat would race a concurrent uploader otherwise.
            DigestInputStream digesting = new DigestInputStream(content, digest);

            // TODO(ADR-006/R3): add SSE headers here once HashiCorp Vault manages
            // per-partner keys (crypto-shred on offboarding). Plain for now.
            minio.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .stream(digesting, -1, PART_SIZE)
                    .contentType(resolvedContentType)
                    .userMetadata(userMetadata)
                    .build());
        } catch (Exception e) {
            throw new VaultException("vault store failed for " + key + ": " + e.getMessage(), e);
        }

        // The digest is only known AFTER the PUT consumed the stream. The ref
        // (persisted by the caller into partner_document.sha256) is the
        // authoritative carrier; the object tag is a best-effort convenience so
        // retrieve() and S3 console inspection see the digest too.
        String sha256 = HexFormat.of().formatHex(digest.digest());
        try {
            minio.setObjectTags(SetObjectTagsArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .tags(Map.of(TAG_SHA256, sha256))
                    .build());
        } catch (Exception e) {
            log.warn("could not tag {} with sha256 (object stored fine): {}", key, e.getMessage());
        }
        return new VaultObjectRef("s3://" + bucket + "/" + key, version, sha256);
    }

    @Override
    public VaultObject retrieve(String uri) {
        String key = keyOf(uri);
        try {
            StatObjectResponse stat = minio.statObject(StatObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());

            String filename = null;
            for (Map.Entry<String, String> meta : stat.userMetadata().entrySet()) {
                // Header-key case differs across S3 implementations; match
                // case-insensitively.
                if (META_FILENAME.equalsIgnoreCase(meta.getKey())) {
                    filename = URLDecoder.decode(meta.getValue(), StandardCharsets.UTF_8);
                }
            }

            String sha256 = null;
            try {
                sha256 = minio.getObjectTags(GetObjectTagsArgs.builder()
                                .bucket(bucket)
                                .object(key)
                                .build())
                        .get()
                        .get(TAG_SHA256);
            } catch (Exception e) {
                // Best-effort: a missing tag never blocks serving the document.
                log.warn("could not read sha256 tag of {}: {}", key, e.getMessage());
            }

            InputStream content = minio.getObject(GetObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .build());
            return new VaultObject(content, filename, stat.contentType(), stat.size(), sha256);
        } catch (VaultException e) {
            throw e;
        } catch (Exception e) {
            throw new VaultException("vault retrieve failed for " + uri + ": " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Object key for a {@code s3://<bucket>/<key>} URI minted by {@link #store}. */
    private String keyOf(String uri) {
        String expectedPrefix = "s3://" + bucket + "/";
        if (uri == null || !uri.startsWith(expectedPrefix) || uri.length() == expectedPrefix.length()) {
            throw new VaultException("not a vault uri for bucket '" + bucket + "': " + uri);
        }
        return uri.substring(expectedPrefix.length());
    }

    /** Count of stored objects under the given key prefix (versions across docIds). */
    private int countObjects(String prefix) {
        int count = 0;
        try {
            Iterable<Result<Item>> results = minio.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .recursive(true)
                    .build());
            for (Result<Item> result : results) {
                result.get(); // surfaces listing errors per item
                count++;
            }
        } catch (Exception e) {
            throw new VaultException("vault listing failed for prefix " + prefix + ": "
                    + e.getMessage(), e);
        }
        return count;
    }

    private static void requireToken(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new VaultException(name + " is required");
        }
        if (value.contains("/") || value.contains("\\") || value.contains("..")) {
            throw new VaultException(name + " must not contain path separators: " + value);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new VaultException("JVM without SHA-256", e);
        }
    }
}
