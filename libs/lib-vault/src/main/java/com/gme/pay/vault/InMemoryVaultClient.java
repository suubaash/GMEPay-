package com.gme.pay.vault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Heap-backed {@link VaultClient} — the dev/test default when no
 * {@code gmepay.vault.endpoint} is configured ({@code @ConditionalOnMissingBean}
 * wiring in {@link InMemoryVaultAutoConfiguration}).
 *
 * <p>Honors the full port contract so service-level tests exercise the real
 * code paths: per-{@code (partnerCode, docType)} version counters, ADR-006 path
 * layout under the configured bucket name, streamed SHA-256, metadata
 * round-trip, and — true to the no-delete contract — no way to remove an entry.
 * URIs use the {@code mem://} scheme so a persisted dev URI is recognizably not
 * an S3 locator.
 *
 * <p>Thread-safe via coarse synchronization; this client backs dev boots and
 * unit tests, not production load.
 */
public class InMemoryVaultClient implements VaultClient {

    /** Default bucket, mirroring the production MinIO bucket name (ADR-006). */
    public static final String DEFAULT_BUCKET = "gmepay-partner-vault";

    private record Stored(byte[] bytes, String filename, String contentType, String sha256) {
    }

    /** key = object path under the bucket (no scheme/bucket prefix). */
    private final Map<String, Stored> objects = new LinkedHashMap<>();

    private final String bucket;

    public InMemoryVaultClient() {
        this(DEFAULT_BUCKET);
    }

    public InMemoryVaultClient(String bucket) {
        this.bucket = bucket;
    }

    @Override
    public synchronized VaultObjectRef store(String partnerCode, String docType, String filename,
                                             String contentType, InputStream content) {
        requireToken("partnerCode", partnerCode);
        requireToken("docType", docType);
        if (filename == null || filename.isBlank()) {
            throw new VaultException("filename is required");
        }
        byte[] bytes;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            content.transferTo(buffer);
            bytes = buffer.toByteArray();
        } catch (IOException e) {
            throw new VaultException("failed reading upload stream", e);
        }
        String sha256 = sha256Hex(bytes);

        // Version = count of prior stores for this (partnerCode, docType) + 1 —
        // same rule MinioVaultClient applies by counting keys under the prefix.
        String prefix = partnerCode + "/" + docType + "/";
        int version = 1 + (int) objects.keySet().stream().filter(k -> k.startsWith(prefix)).count();

        String key = prefix + UUID.randomUUID() + "/v" + version + extensionOf(filename);
        objects.put(key, new Stored(bytes, filename,
                contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType,
                sha256));
        return new VaultObjectRef("mem://" + bucket + "/" + key, version, sha256);
    }

    @Override
    public synchronized VaultObject retrieve(String uri) {
        String expectedPrefix = "mem://" + bucket + "/";
        if (uri == null || !uri.startsWith(expectedPrefix)) {
            throw new VaultException("not an in-memory vault uri: " + uri);
        }
        Stored stored = objects.get(uri.substring(expectedPrefix.length()));
        if (stored == null) {
            throw new VaultException("no vault object at " + uri);
        }
        return new VaultObject(new ByteArrayInputStream(stored.bytes()), stored.filename(),
                stored.contentType(), stored.bytes().length, stored.sha256());
    }

    /** Number of stored objects — test observability only. */
    public synchronized int size() {
        return objects.size();
    }

    // ------------------------------------------------------------------ helpers

    /** Reject path tokens that would corrupt the key layout (or path-traverse). */
    private static void requireToken(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new VaultException(name + " is required");
        }
        if (value.contains("/") || value.contains("\\") || value.contains("..")) {
            throw new VaultException(name + " must not contain path separators: " + value);
        }
    }

    /** Lowercased {@code .ext} of the filename, or empty when there is none. */
    static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0 || dot == filename.length() - 1) {
            return "";
        }
        String ext = filename.substring(dot + 1);
        // Only keep simple alphanumeric extensions; anything exotic is dropped
        // rather than risking an unexpected character in the object key.
        if (!ext.matches("[A-Za-z0-9]{1,10}")) {
            return "";
        }
        return "." + ext.toLowerCase(java.util.Locale.ROOT);
    }

    static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new VaultException("JVM without SHA-256", e);
        }
    }
}
