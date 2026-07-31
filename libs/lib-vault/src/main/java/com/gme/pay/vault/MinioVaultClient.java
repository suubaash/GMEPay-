package com.gme.pay.vault;

import io.minio.GetObjectArgs;
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
import io.minio.messages.Item;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * <h2>Version assignment is a claim, not a count</h2>
 *
 * <p>This client used to derive {@code v<n>} by listing the
 * {@code (partnerCode, docType)} prefix and adding one — a read-modify-write
 * with no compare-and-set. Two concurrent uploads both read the same count and
 * both minted {@code vN}. Because the {@code docId} segment is a fresh UUID per
 * call the two objects landed on <b>different keys</b>, so no bytes were lost;
 * what was lost is the thing the version number exists for — <b>which KYB
 * document supersedes which</b>. Two rows in {@code partner_document} claiming
 * to be "version 2" of the same business licence, in a bucket whose object-lock
 * COMPLIANCE retention means neither can be deleted and redone, is a
 * compliance-evidence defect rather than a cosmetic one.
 *
 * <p>Version numbers are therefore <b>claimed</b> before the document is
 * written, through an append-only ledger inside the same prefix:
 *
 * <pre>{@code <partnerCode>/<docType>/_versions/v<n>}</pre>
 *
 * <p>Each claim is a zero-cost marker object carrying the claiming writer's
 * token. The claim PUT is made <b>conditional</b> ({@code If-None-Match: *}, the
 * S3 conditional-write primitive), and is then <b>verified</b> by listing every
 * object version of that exact claim key:
 *
 * <ul>
 *   <li>a server that honours the precondition rejects the second writer with
 *       {@code 412 PreconditionFailed} — one winner, one clear failure;</li>
 *   <li>a server that <em>ignores</em> the precondition (older MinIO releases,
 *       and any S3 predating Aug-2024 conditional writes) silently creates a
 *       second object version of the claim key — which the verification listing
 *       sees, so both writers fail closed rather than one silently winning.</li>
 * </ul>
 *
 * <p>The verification is what makes the fix safe on the MinIO release pinned in
 * {@code docker-compose.yml}; the conditional header is what makes it cheap and
 * single-winner everywhere it is supported. Neither path ever renumbers behind
 * the caller's back: a loser gets {@link VaultVersionConflictException} and
 * decides for itself (see that class for why silent renumbering is wrong here).
 *
 * <p>Consequences worth knowing:
 * <ul>
 *   <li><b>Version numbers can be sparse.</b> A claim that succeeds and is then
 *       followed by a failed document PUT — or a lost race — burns its number
 *       permanently (object-lock: the marker cannot be removed). Versions stay
 *       strictly increasing and never repeat, which is the property readers
 *       depend on; they are not guaranteed contiguous. Nothing reads them as
 *       contiguous: {@link #retrieve} addresses the exact key, and
 *       config-registry resolves "current" from {@code superseded_at IS NULL},
 *       not from {@code MAX(version)}.</li>
 *   <li>The ledger doubles as an audit trail of who claimed what and when,
 *       retained for the same 10 years as the documents.</li>
 * </ul>
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

    /**
     * Path segment holding the append-only version-claim ledger of one
     * {@code (partnerCode, docType)} pair. Sits where a {@code docId} would, and
     * can never collide with one: {@code docId} is always a UUID.
     */
    static final String VERSION_LEDGER_SEGMENT = "_versions";

    /**
     * S3 conditional-write precondition: "only create this object if the key
     * does not already exist". Honoured by AWS S3 since Aug 2024 and by recent
     * MinIO releases; older servers ignore it, which the post-PUT verification
     * covers.
     */
    private static final String IF_NONE_MATCH = "If-None-Match";

    /** Error codes meaning "another writer already created the claim key". */
    private static final List<String> CONFLICT_CODES =
            List.of("preconditionfailed", "conditionalrequestconflict", "operationaborted");

    /** Error codes meaning "this server does not implement conditional writes". */
    private static final List<String> UNSUPPORTED_CODES =
            List.of("notimplemented", "invalidargument", "invalidrequest", "badrequest");

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
        // Compare-and-set: either this call owns version N exclusively, or it
        // throws. Never "probably owns it".
        int version = claimNextVersion(prefix);
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
            // The claim marker for `version` stays behind on purpose: object-lock
            // forbids removing it, and leaving it burns the number so no later
            // upload can reuse a version whose bytes may or may not have landed.
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

    // ------------------------------------------------------- version claiming

    /**
     * Reserve the next version of one {@code (partnerCode, docType)} prefix
     * exclusively, or fail.
     *
     * @param prefix {@code <partnerCode>/<docType>/}.
     * @return the claimed version, owned by this call alone.
     * @throws VaultVersionConflictException another writer claimed the same
     *         number concurrently; nothing was stored.
     */
    private int claimNextVersion(String prefix) {
        int next = highestVersion(prefix) + 1;
        String claimKey = prefix + VERSION_LEDGER_SEGMENT + "/v" + next;
        String writerToken = UUID.randomUUID().toString();

        String ourVersionId = putClaimMarker(claimKey, writerToken, next);
        verifySoleClaimant(claimKey, ourVersionId, next);
        return next;
    }

    /**
     * PUT the zero-cost claim marker, conditionally when the server supports it.
     *
     * @return the S3 version id of the object version this call created, or
     *         {@code null} when the server reported none (unversioned bucket).
     */
    private String putClaimMarker(String claimKey, String writerToken, int version) {
        try {
            return putClaim(claimKey, writerToken, true);
        } catch (ErrorResponseException e) {
            String code = errorCode(e);
            if (CONFLICT_CODES.contains(code)) {
                // The server honours conditional writes and told us we lost.
                throw new VaultVersionConflictException(
                        "vault version v" + version + " was claimed by a concurrent upload of the"
                                + " same document type (" + claimKey + "); nothing was stored —"
                                + " retry the upload to take the next version", e);
            }
            if (!UNSUPPORTED_CODES.contains(code)) {
                throw new VaultException("vault version claim failed for " + claimKey + ": "
                        + e.getMessage(), e);
            }
            // Server rejects the precondition header itself (pre-conditional-write
            // S3/MinIO). Fall back to an unconditional PUT — verifySoleClaimant
            // below is what keeps that safe.
            log.debug("vault endpoint rejected {} on {} (code {}); relying on version-listing"
                    + " verification instead", IF_NONE_MATCH, claimKey, code);
            try {
                return putClaim(claimKey, writerToken, false);
            } catch (Exception retry) {
                throw new VaultException("vault version claim failed for " + claimKey + ": "
                        + retry.getMessage(), retry);
            }
        } catch (Exception e) {
            throw new VaultException("vault version claim failed for " + claimKey + ": "
                    + e.getMessage(), e);
        }
    }

    private String putClaim(String claimKey, String writerToken, boolean conditional)
            throws Exception {
        byte[] body = writerToken.getBytes(StandardCharsets.UTF_8);
        PutObjectArgs.Builder builder = PutObjectArgs.builder()
                .bucket(bucket)
                .object(claimKey)
                .stream(new ByteArrayInputStream(body), body.length, -1)
                .contentType("text/plain");
        if (conditional) {
            builder.extraHeaders(Map.of(IF_NONE_MATCH, "*"));
        }
        ObjectWriteResponse response = minio.putObject(builder.build());
        return response == null ? null : response.versionId();
    }

    /**
     * Prove that the claim key holds exactly one object version and that it is
     * ours. This is the half that survives a server which silently ignored
     * {@code If-None-Match} — two writers then produce two versions of the claim
     * key and BOTH see it, so both fail closed. Failing both is correct: it is
     * the only outcome that never lets a colliding write win silently, and a
     * retry costs one number in an append-only ledger.
     */
    private void verifySoleClaimant(String claimKey, String ourVersionId, int version) {
        List<Item> claims;
        try {
            claims = versionsOf(claimKey);
        } catch (Exception e) {
            throw new VaultException("vault could not verify version claim " + claimKey + ": "
                    + e.getMessage(), e);
        }
        if (claims.size() > 1) {
            throw new VaultVersionConflictException(
                    "vault version v" + version + " was claimed " + claims.size() + " times"
                            + " concurrently (" + claimKey + "); the endpoint does not enforce"
                            + " conditional writes, so NO writer is allowed to win — nothing was"
                            + " stored, retry the upload to take the next version");
        }
        if (claims.isEmpty()) {
            throw new VaultException("vault version claim " + claimKey + " disappeared immediately"
                    + " after it was written — refusing to store against an unproven version");
        }
        String seen = claims.get(0).versionId();
        if (ourVersionId == null || seen == null || seen.isBlank() || "null".equals(seen)) {
            // Unversioned bucket (or a server that returns no version id): the
            // conditional PUT is then the only guard. VaultBucketInitializer
            // always creates the bucket with object-lock, which implies
            // versioning, so this means the bucket was hand-made without it.
            log.warn("vault bucket '{}' reports no object versions — version-claim collisions can"
                    + " only be caught by the {} precondition. Recreate the bucket with object-lock"
                    + " (ADR-006) so versioning is on.", bucket, IF_NONE_MATCH);
            return;
        }
        if (!seen.equals(ourVersionId)) {
            throw new VaultVersionConflictException(
                    "vault version v" + version + " (" + claimKey + ") is held by another writer's"
                            + " object version " + seen + ", not ours (" + ourVersionId + ");"
                            + " nothing was stored — retry the upload to take the next version");
        }
    }

    /** Every non-delete-marker object version of one exact key. */
    private List<Item> versionsOf(String key) throws Exception {
        List<Item> found = new ArrayList<>();
        Iterable<Result<Item>> results = minio.listObjects(ListObjectsArgs.builder()
                .bucket(bucket)
                .prefix(key)
                .recursive(true)
                .includeVersions(true)
                .build());
        for (Result<Item> result : results) {
            Item item = result.get();
            if (item != null && key.equals(item.objectName()) && !item.isDeleteMarker()) {
                found.add(item);
            }
        }
        return found;
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

    /**
     * Highest version number currently visible under the prefix — across BOTH
     * the claim ledger and the document objects themselves.
     *
     * <p>Reading the documents too (not just the ledger) is what makes this
     * backward compatible: a bucket written by the previous count-then-write
     * client has documents but no ledger, and must not restart numbering at 1.
     * Reading the ledger too is what makes burned numbers stick.
     *
     * <p>This is still a plain read — it is the {@link #claimNextVersion}
     * conditional PUT that decides who gets {@code highest + 1}.
     */
    private int highestVersion(String prefix) {
        int highest = 0;
        try {
            Iterable<Result<Item>> results = minio.listObjects(ListObjectsArgs.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .recursive(true)
                    .build());
            for (Result<Item> result : results) {
                Item item = result.get(); // surfaces listing errors per item
                if (item == null || item.isDeleteMarker()) {
                    continue;
                }
                highest = Math.max(highest, versionOfKey(prefix, item.objectName()));
            }
        } catch (Exception e) {
            throw new VaultException("vault listing failed for prefix " + prefix + ": "
                    + e.getMessage(), e);
        }
        return highest;
    }

    /**
     * Version number encoded in a key's leaf segment, or {@code 0} when the key
     * carries none. Handles both shapes that live under the prefix:
     * {@code <docId>/v3.pdf} (a document) and {@code _versions/v3} (a claim).
     */
    static int versionOfKey(String prefix, String key) {
        if (key == null || !key.startsWith(prefix)) {
            return 0;
        }
        String rest = key.substring(prefix.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return 0;
        }
        String leaf = rest.substring(slash + 1);
        if (leaf.length() < 2 || (leaf.charAt(0) != 'v' && leaf.charAt(0) != 'V')) {
            return 0;
        }
        int dot = leaf.indexOf('.');
        String digits = dot < 0 ? leaf.substring(1) : leaf.substring(1, dot);
        // >9 digits cannot be a real version and would overflow the int.
        if (digits.isEmpty() || digits.length() > 9) {
            return 0;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                return 0;
            }
        }
        return Integer.parseInt(digits);
    }

    /** Lowercased S3 error code of a MinIO error response, or {@code ""}. */
    private static String errorCode(ErrorResponseException e) {
        if (e.errorResponse() == null || e.errorResponse().code() == null) {
            return "";
        }
        return e.errorResponse().code().toLowerCase(Locale.ROOT);
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
