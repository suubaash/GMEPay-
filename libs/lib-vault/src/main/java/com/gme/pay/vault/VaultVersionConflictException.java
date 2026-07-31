package com.gme.pay.vault;

/**
 * Thrown by {@link VaultClient#store} when a <b>concurrent writer claimed the
 * same version</b> of the same {@code (partnerCode, docType)} and this call
 * lost (or could not prove it won) the race.
 *
 * <h2>What it guarantees</h2>
 *
 * <p>Nothing was written for this call: the document bytes were NOT stored, and
 * no {@link VaultObjectRef} was minted. The version number this call was
 * competing for belongs to somebody else — or, in the both-lose case, to
 * nobody. The vault deliberately fails rather than renumbering silently,
 * because renumbering would leave the caller's own metadata row (the
 * {@code partner_document} SCD-6 pair in config-registry) racing the other
 * writer's with no ordering anyone can reconstruct afterwards, in a bucket
 * whose object-lock COMPLIANCE retention makes the mistake permanent.
 *
 * <h2>What the caller should do</h2>
 *
 * <p>Retry the whole upload, or surface a 409-shaped "someone else is
 * uploading this document type right now, try again" to the operator. A retry
 * is safe and will pick the next free version: version numbers are claimed in
 * an append-only ledger, never reused, so a burned number simply leaves a gap.
 * Do NOT catch this and reuse the version.
 *
 * <p>It extends {@link VaultException} so existing callers that only know the
 * port's single failure type keep compiling and keep failing closed (today
 * config-registry maps every {@code VaultException} to 502; mapping this
 * subtype to 409 is a caller-side follow-up).
 */
public class VaultVersionConflictException extends VaultException {

    public VaultVersionConflictException(String message) {
        super(message);
    }

    public VaultVersionConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
