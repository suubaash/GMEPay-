package com.gme.pay.vault;

import java.io.InputStream;

/**
 * Partner document vault port per ADR-006 — the ONLY surface services use to
 * touch regulated document storage. Services never talk to MinIO directly.
 *
 * <h2>Write-once, version-forever</h2>
 *
 * <p>There is deliberately <b>no delete method</b> on this interface. The vault
 * bucket runs object-lock in compliance mode (10-year retention, ADR-006):
 * even bucket admins cannot delete during retention, so offering a delete API
 * would be a lie waiting for a runtime error. Correcting a document means
 * storing a new version — {@link #store} on the same
 * {@code (partnerCode, docType)} pair mints {@code v<n+1>} alongside the
 * immutable prior versions. Partner offboarding under PIPA Art. 21 is handled
 * by crypto-shredding the per-partner encryption key (R3, Vault-managed keys),
 * not by removing objects.
 *
 * <h2>Path layout</h2>
 *
 * <pre>{@code <bucket>/<partnerCode>/<docType>/<docId>/v<n>[.<ext>]}</pre>
 *
 * <p>{@code docId} is minted by the implementation per store call; the version
 * counter is per {@code (partnerCode, docType)} pair. The full locator comes
 * back as {@link VaultObjectRef#uri()} and is what callers persist.
 *
 * <h2>Version numbers: strictly increasing, not necessarily contiguous</h2>
 *
 * <p>Versions of one {@code (partnerCode, docType)} pair are <b>claimed
 * exclusively</b> before the bytes are written, so two concurrent uploads can
 * never both be told they are {@code vN}. The price of that guarantee is that
 * numbers can be <b>skipped</b>: a claim whose document write then fails, or a
 * writer that loses a race, burns its number permanently (object-lock forbids
 * taking it back). Callers may rely on "a higher version was stored later" and
 * on "no two stored documents of one type share a version"; they may NOT rely
 * on {@code v1, v2, v3, …} without gaps, and must not compute the next version
 * themselves.
 *
 * <p>A concurrent claim on the same pair fails the losing call with
 * {@link VaultVersionConflictException} (a {@link VaultException} subtype)
 * <b>before anything is stored</b>. The vault never renumbers silently — see
 * that class for why. A caller may retry the whole upload; it will take the
 * next free number.
 *
 * <h2>Implementations</h2>
 *
 * <ul>
 *   <li>{@link MinioVaultClient} — production, bucket
 *       {@code gmepay-partner-vault}; active when {@code gmepay.vault.endpoint}
 *       is configured.</li>
 *   <li>{@link InMemoryVaultClient} — dev/test default
 *       ({@code @ConditionalOnMissingBean}); same contract, heap-backed.</li>
 * </ul>
 */
public interface VaultClient {

    /**
     * Stream one document into the vault.
     *
     * @param partnerCode the partner business code (first path segment).
     * @param docType     the document type discriminator (second path segment),
     *                    e.g. {@code LICENSE}. The vault treats it as an opaque
     *                    path token; the calling service enforces the roster.
     * @param filename    original upload filename (recorded as metadata; its
     *                    extension is appended to the {@code v<n>} object name
     *                    per ADR-006).
     * @param contentType MIME type to record; {@code null} falls back to
     *                    {@code application/octet-stream}.
     * @param content     the bytes to store. Fully consumed; NOT closed by the
     *                    vault client (caller owns it).
     * @return locator + version + SHA-256 of the stored object.
     * @throws VaultVersionConflictException when a concurrent upload of the same
     *                        {@code (partnerCode, docType)} claimed the same
     *                        version. Nothing was stored; retrying is safe.
     * @throws VaultException when the backend rejects the write or is
     *                        unreachable.
     */
    VaultObjectRef store(String partnerCode, String docType, String filename,
                         String contentType, InputStream content);

    /**
     * Fetch a previously-stored object by the URI a {@link #store} call
     * returned.
     *
     * @param uri the opaque locator from {@link VaultObjectRef#uri()}.
     * @return content stream + metadata; caller must close the stream.
     * @throws VaultException when the URI is unknown, malformed, or the backend
     *                        is unreachable.
     */
    VaultObject retrieve(String uri);
}
