package com.gme.pay.contracts;

import java.time.Instant;

/**
 * The ONE-TIME plaintext credential bundle returned on issuance / rotation
 * (Slice 8 Lane B — see {@code docs/PARTNER_SETUP_PLAN.md} §"Slice 8").
 *
 * <h2>⚠ SECURITY — READ BEFORE TOUCHING (SEC-09 §4)</h2>
 *
 * <p>This record is the ONLY moment the plaintext API key / HMAC signing
 * secret / webhook signing secret exist outside auth-identity's hashing path.
 * It MUST NEVER be:
 * <ul>
 *   <li><b>logged</b> — not at any level, not in exception messages, not in
 *       audit snapshots ({@link #toString()} is overridden to redact the
 *       secret components precisely so an accidental {@code log.info("{}",
 *       bundle)} leaks nothing — do not "fix" that override);</li>
 *   <li><b>persisted</b> — not in any database column, cache, queue payload
 *       or vault object. auth-identity stores the salted PBKDF2 hash;
 *       config-registry's {@code partner_credential} ledger stores only the
 *       key id + prefix + last 4 ({@link PartnerCredentialView});</li>
 *   <li><b>re-fetched</b> — there is no endpoint that returns it again. A
 *       lost secret means rotation, by design.</li>
 * </ul>
 *
 * <p>The bundle rides the activation / rotation HTTP response exactly once;
 * the caller (admin UI) renders it with a copy-once affordance and drops it.
 *
 * @param apiKeyPlaintext        the full API key the partner presents in
 *                               {@code X-API-Key} (e.g. {@code pk_live_…}).
 * @param hmacSecretPlaintext    the HMAC-SHA256 signing secret paired with the
 *                               API key (e.g. {@code sk_live_…}).
 * @param webhookSecretPlaintext the webhook signature secret (e.g.
 *                               {@code whsec_live_…}).
 * @param apiKeyId               auth-identity's public key identifier for the
 *                               API-key row — safe to display and persist.
 * @param prefix                 display prefix of the API key ({@code pk_test_}
 *                               / {@code pk_live_}).
 * @param last4                  last 4 characters of the API key, for display.
 * @param expiresAt              when the issued material expires (rotation is
 *                               proposed at the 11-month mark, ahead of this).
 */
public record IssuedCredentialBundle(
        String apiKeyPlaintext,
        String hmacSecretPlaintext,
        String webhookSecretPlaintext,
        String apiKeyId,
        String prefix,
        String last4,
        Instant expiresAt) {

    /**
     * Redacting override — the canonical record {@code toString()} would
     * include every component, so a stray logger call would leak all three
     * secrets. Only the display-safe residue is rendered.
     */
    @Override
    public String toString() {
        return "IssuedCredentialBundle[apiKeyPlaintext=REDACTED,"
                + " hmacSecretPlaintext=REDACTED, webhookSecretPlaintext=REDACTED,"
                + " apiKeyId=" + apiKeyId
                + ", prefix=" + prefix
                + ", last4=" + last4
                + ", expiresAt=" + expiresAt + "]";
    }
}
