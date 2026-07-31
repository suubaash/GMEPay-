-- Gap T5-4: per-endpoint webhook signing secrets + rotation.
--
-- BEFORE: every partner's webhook was signed with the ONE global
--         gmepay.webhook.signing-secret (DefaultWebhookTargetResolver returned
--         configuredSecret for every endpoint). One leaked value forged events to
--         every partner, and the whsec_ secret handed out at activation was never
--         actually used to sign anything.
--
-- AFTER:  the secret is DERIVED per endpoint — HKDF-SHA256(root key, info =
--         partner|environment|generation), see WebhookSecretDeriver. Still nothing
--         but the SHA-256 digest at rest (signing_secret_hash, V004): the dispatcher
--         re-derives the plaintext and CHECKS it against the digest before signing,
--         so a wrong/absent root key produces no delivery instead of a wrong signature.
--
-- Columns added:
--   secret_generation          rotation counter that is part of the HKDF info string.
--                              Bumping it yields an independent secret. 1 = never rotated.
--   previous_secret_hash       digest of the generation-before-last secret, kept for the
--                              rotation OVERLAP window: while it is live the dispatcher
--                              sends BOTH signatures in X-GME-Webhook-Signature
--                              (comma-separated, Stripe-style) so a partner can cut over
--                              without dropping events. NULL = no overlap in force.
--   previous_secret_expires_at end of that overlap window. Past it, only the current
--                              generation is signed with (the resolver stops re-deriving
--                              the old one even if the columns are still populated).
--
-- PostgreSQL-compatible SQL that also runs on H2 in PostgreSQL mode (plain TIMESTAMP,
-- no vendor-only syntax) — same discipline as V001-V006.
ALTER TABLE webhook_endpoint
    ADD COLUMN secret_generation INTEGER NOT NULL DEFAULT 1;

ALTER TABLE webhook_endpoint
    ADD COLUMN previous_secret_hash VARCHAR(64);

ALTER TABLE webhook_endpoint
    ADD COLUMN previous_secret_expires_at TIMESTAMP;

ALTER TABLE webhook_endpoint
    ADD CONSTRAINT ck_webhook_endpoint_secret_generation
        CHECK (secret_generation >= 1);
