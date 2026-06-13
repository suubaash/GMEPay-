package com.gme.pay.vault;

/**
 * Immutable reference to one stored vault object — what {@link VaultClient#store}
 * hands back and what callers persist (e.g. {@code partner_document.vault_uri} /
 * {@code version} / {@code sha256} in config-registry).
 *
 * <ul>
 *   <li>{@code uri} — opaque locator of the object,
 *       {@code <scheme>://<bucket>/<partnerCode>/<docType>/<docId>/v<n>[.<ext>]}
 *       per the ADR-006 path layout. Callers MUST treat it as opaque and feed it
 *       back to {@link VaultClient#retrieve} unchanged; the scheme differs per
 *       backend ({@code s3://} for MinIO, {@code mem://} for the in-memory dev
 *       client).</li>
 *   <li>{@code version} — 1-based monotonic version of this document type for
 *       the partner ({@code v<n>} path segment). The vault NEVER overwrites:
 *       a re-upload of the same {@code (partnerCode, docType)} pair mints
 *       {@code v<n+1>} alongside the immutable prior versions (object-lock,
 *       ADR-006).</li>
 *   <li>{@code sha256} — lowercase hex SHA-256 (64 chars) of the stored bytes,
 *       computed while streaming. Persisted so audits can prove the bytes served
 *       back are the bytes that were stored.</li>
 * </ul>
 */
public record VaultObjectRef(String uri, int version, String sha256) {
}
