-- V004: Slice 8 Lane D — partner-activation webhook provisioning.
--
-- Two additive columns on webhook_endpoint (ADR-013 Expand discipline; no
-- in-place type changes, both columns backfill-safe for the V003 rows):
--
--   * environment           — which credential environment the registration
--                             belongs to (SANDBOX | LIVE). Partner activation
--                             provisions SANDBOX first, LIVE later; the pair
--                             (partner_id, environment) is the idempotency key
--                             the registration endpoint resolves on. Existing
--                             V003 rows default to SANDBOX.
--
--   * signing_secret_hash   — SHA-256 (lowercase hex, 64 chars) of the HMAC
--                             signing secret generated at registration time.
--                             SECURITY CONVENTION (SEC-09 §4, same rule as
--                             auth-identity's api_keys): the PLAINTEXT secret
--                             is NEVER persisted — it is returned exactly once
--                             in the registration response (and routed to
--                             Vault for dispatch-time signing); this column
--                             stores only the one-way digest so a presented
--                             candidate can be verified. The secret is a
--                             256-bit CSPRNG value, so an unsalted SHA-256 is
--                             cryptographically sufficient (no low-entropy
--                             password to brute-force; cf. SecretHasher's
--                             PBKDF2 which exists for the same reason on the
--                             API-key path). NULL on legacy V003 rows whose
--                             secrets were Vault-only.
--
-- PostgreSQL-compatible SQL that also works under H2 PostgreSQL mode
-- (plain TIMESTAMP elsewhere, no JSONB, no vendor-only syntax).
ALTER TABLE webhook_endpoint
    ADD COLUMN environment VARCHAR(10) NOT NULL DEFAULT 'SANDBOX';

ALTER TABLE webhook_endpoint
    ADD COLUMN signing_secret_hash VARCHAR(64);

ALTER TABLE webhook_endpoint
    ADD CONSTRAINT ck_webhook_endpoint_environment
        CHECK (environment IN ('SANDBOX', 'LIVE'));

-- Idempotency lookup: "active endpoint for partner P in environment E".
CREATE INDEX idx_webhook_endpoint_partner_env_active
    ON webhook_endpoint(partner_id, environment, active);
