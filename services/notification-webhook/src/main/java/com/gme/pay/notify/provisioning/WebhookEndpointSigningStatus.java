package com.gme.pay.notify.provisioning;

/**
 * Whether one {@code webhook_endpoint} row can be signed for — gap <b>T5-8</b>.
 *
 * <p>T5-4 replaced the single global HMAC key with per-endpoint HKDF-derived secrets and
 * made {@code DefaultWebhookTargetResolver} <b>verify the re-derived secret against the
 * row's {@code signing_secret_hash} before signing</b>. That is the correct behaviour, but
 * it silently split the endpoint population in two: rows minted after the change are
 * signable, and rows minted before it are NOT — their digests are of CSPRNG secrets whose
 * plaintext was never stored anywhere, so no derivation can ever reproduce them.
 *
 * <p>Until T5-8 the only evidence of that split was an ERROR line per delivery attempt.
 * This enum is the same rule the resolver applies, made <b>readable</b>, so an operator can
 * see which endpoints are dead before a partner reports missing events.
 *
 * <p>Every value except {@link #SIGNABLE} means "deliveries for this endpoint will be left
 * PENDING". Only {@link #SECRET_NOT_DERIVABLE} and {@link #NO_SECRET_DIGEST} are fixed by
 * rotation; {@link #ROOT_KEY_MISSING} is a platform misconfiguration that rotation cannot
 * and must not paper over.
 */
public enum WebhookEndpointSigningStatus {

    /**
     * The secret re-derives to exactly the digest stored at registration/rotation — the
     * dispatcher will sign with the very secret this partner holds.
     */
    SIGNABLE(true, false,
            "signable: the derived secret matches the digest stored for this endpoint"),

    /**
     * No derivation root key is configured ({@code gmepay.webhook.signing-secret}), so
     * NOTHING can be derived for any endpoint. Not per-endpoint damage and not fixable by
     * rotating — rotation is refused in this state on purpose, because we must never issue
     * a secret the dispatcher could not reproduce.
     */
    ROOT_KEY_MISSING(false, false,
            "the derivation root key gmepay.webhook.signing-secret is not configured, so no "
                    + "endpoint can be signed for; set it before rotating anything"),

    /**
     * The row's digest is not the digest of this endpoint's derivable secret. This is the
     * T5-8 population: endpoints registered before per-endpoint derivation existed (their
     * secret was unrelated CSPRNG randomness), or minted while the root key was blank, or a
     * root key that has since changed. <b>Rotation fixes it</b> — and the partner must be
     * told the new secret.
     */
    SECRET_NOT_DERIVABLE(false, true,
            "the secret stored for this endpoint cannot be re-derived (registered before "
                    + "per-endpoint derivation, or minted with a different/blank root key); "
                    + "deliveries refuse to sign until it is rotated"),

    /**
     * Legacy V003 row with {@code signing_secret_hash IS NULL} — the secret was Vault-only,
     * so there is nothing to verify a derived value against. Fixed by rotation.
     */
    NO_SECRET_DIGEST(false, true,
            "this endpoint carries no signing-secret digest (legacy Vault-only row), so a "
                    + "derived secret cannot be proven correct; rotate to establish one");

    private final boolean deliverable;
    private final boolean fixableByRotation;
    private final String detail;

    WebhookEndpointSigningStatus(boolean deliverable, boolean fixableByRotation, String detail) {
        this.deliverable = deliverable;
        this.fixableByRotation = fixableByRotation;
        this.detail = detail;
    }

    /** True only for {@link #SIGNABLE}: whether a delivery for this endpoint can be signed. */
    public boolean isDeliverable() {
        return deliverable;
    }

    /**
     * True when {@code POST /v1/webhooks/endpoints/{id}/rotate-secret} makes this endpoint
     * deliverable again. False for {@link #SIGNABLE} (nothing to fix) and for
     * {@link #ROOT_KEY_MISSING} (fix the configuration, not the endpoint).
     */
    public boolean isFixableByRotation() {
        return fixableByRotation;
    }

    /** Operator-facing explanation — surfaced verbatim by the ops BFF / Admin UI. */
    public String detail() {
        return detail;
    }
}
