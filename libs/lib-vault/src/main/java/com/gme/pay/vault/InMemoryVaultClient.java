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
 *
 * <h2>Per-JVM state — INCORRECT above one replica, not merely volatile</h2>
 *
 * <p>Its own javadoc used to warn only about restart loss. The multi-replica failure is worse and
 * quieter: {@link #objects} is one heap map, so a document stored through pod A is
 * {@code "no vault object at …"} from pod B, and the version number at {@code store(..)} is derived
 * by counting the keys <em>this JVM</em> holds under the {@code (partnerCode, docType)} prefix — so
 * two pods both mint {@code v1} for two different uploads of the same document type. Version is how
 * a reviewer tells the superseded KYB document from the current one; a missing object is an obvious
 * error, two different {@code v1}s is not.
 *
 * <p><b>Verdict: harmless in every deployed environment, and not fixed here.</b>
 * {@link InMemoryVaultAutoConfiguration} registers this bean under
 * {@code @ConditionalOnMissingBean}, and {@code GMEPAY_VAULT_ENDPOINT} is set in
 * {@code docker-compose.yml} and in all four Helm values files, so {@link MinioVaultClient} owns the
 * port everywhere it matters and this class is reachable only on a laptop that pointed at no vault.
 * Sharing the map would mean building an object store, which is what the S3/MinIO client already is.
 * The startup WARN now names the N&gt;1 consequence so the fallback cannot be mistaken for a
 * replica-safe one.
 *
 * <h2>The production client no longer shares this defect — this one still has it</h2>
 *
 * <p>{@link MinioVaultClient} used to derive its version the same way (count the objects under the
 * prefix, add one), a read-modify-write with no compare-and-set. <b>That is fixed:</b> it now claims
 * {@code v<n>} exclusively through a conditional PUT against an append-only ledger key and throws
 * {@link VaultVersionConflictException} when it loses the race, so a colliding write fails loudly
 * instead of quietly producing a second {@code vN}.
 *
 * <p>This client cannot do the same, and does not pretend to. Its counter is exact <em>within one
 * JVM</em> — {@link #store} is {@code synchronized}, so two threads here can never collide, and
 * {@link VaultVersionConflictException} is consequently never thrown. Across JVMs it has nothing to
 * compare-and-set against: two pods each count their own map and each mint {@code v1}, silently.
 * <b>The two implementations therefore differ in their concurrency contract above one replica</b>,
 * which is safe only because this one is unreachable in every deployed environment (see the verdict
 * above) — and is stated in the startup WARN so nobody discovers it from the data.
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

        // Version = count of prior stores for this (partnerCode, docType) + 1.
        // Exact under `synchronized` because this map is the whole universe of a
        // single JVM — and wrong the moment there are two, which is the divergence
        // from MinioVaultClient's claim-based counter documented in the class
        // javadoc and named in the startup WARN.
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
