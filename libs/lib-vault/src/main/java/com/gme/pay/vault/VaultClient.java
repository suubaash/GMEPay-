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
