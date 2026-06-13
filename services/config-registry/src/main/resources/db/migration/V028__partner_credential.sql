-- V028: partner_credential ledger table — Slice 8 Lane B "Credentials"
-- (docs/PARTNER_SETUP_PLAN.md §Slice 8).
--
-- WHY
-- ---
-- Registry-side ledger of every credential issued to a partner. The actual
-- api_keys rows — the salted PBKDF2 hashes — live in auth-identity (its V002,
-- MSA rule 1: auth-identity owns its DB); this table stores ONLY the
-- cross-service reference (auth_identity_key_id), the display prefix and the
-- last 4 characters, so the admin UI can list / rotate credentials and the
-- 11-month rotation scheduler can sweep them WITHOUT a cross-service join.
--
-- SECURITY (SEC-09 §4)
-- --------------------
-- NO SECRET MATERIAL EVER LANDS IN THIS TABLE. No plaintext, no hash, no
-- salt — auth-identity owns those. prefix + last_4 are the display-safe
-- residue ('pk_live_', '3f9c'); auth_identity_key_id is the public key
-- identifier (auth-identity's api_keys.api_key column — explicitly NOT secret
-- material per its V002 contract). Plaintext is returned to the caller
-- exactly once at issuance time (IssuedCredentialBundle) and never persisted.
--
-- WHY NOT BITEMPORAL
-- ------------------
-- A ledger row IS the event ("credential K issued at T, rotated at T2") — the
-- history is the row set itself, not row versions. Lifecycle transitions
-- stamp rotated_at / revoked_at + status in place; every mutation is audited
-- (ADR-007) with BEFORE/AFTER snapshots.
--
-- COMPATIBILITY
-- -------------
-- Plain TIMESTAMP, not TIMESTAMPTZ (H2 PG-mode compat, same as V004..V027).
-- BIGINT GENERATED ALWAYS AS IDENTITY surrogate id, engine-managed.
-- ADR-013 Expand discipline: wholly-new table; no in-place ALTERs.

CREATE TABLE partner_credential (
    id                   BIGINT      GENERATED ALWAYS AS IDENTITY,

    -- FK to the partners surrogate (V003/V004 BIGINT PK); consumers resolve
    -- partners by partner_code (same note as V009..V027).
    partner_id           BIGINT      NOT NULL,

    -- Which endpoint tier the credential authenticates against.
    environment          VARCHAR(20) NOT NULL,

    -- What kind of secret the auth-identity row carries. API_KEY and
    -- HMAC_SECRET are issued as a pair (one auth-identity api_keys row: the
    -- public key identifier + its hashed signing secret) but ledgered as two
    -- rows so each kind lists / rotates / displays independently.
    credential_kind      VARCHAR(20) NOT NULL,

    -- Cross-service reference to auth-identity's api_keys.api_key public
    -- identifier (VARCHAR(64) there too). An id, NEVER a secret. No FK
    -- constraint — the row lives in another service's database (MSA rule 1).
    auth_identity_key_id VARCHAR(64),

    -- Display-safe residue of the issued material.
    prefix               VARCHAR(20) NOT NULL,
    last_4               CHAR(4),

    issued_at            TIMESTAMP   NOT NULL,
    expires_at           TIMESTAMP,
    rotated_at           TIMESTAMP,
    revoked_at           TIMESTAMP,

    status               VARCHAR(20) NOT NULL,

    CONSTRAINT pk_partner_credential PRIMARY KEY (id),

    CONSTRAINT fk_partner_credential_partner
        FOREIGN KEY (partner_id) REFERENCES partners (id),

    CONSTRAINT ck_partner_credential_env CHECK (
        environment IN ('SANDBOX', 'PRODUCTION')
    ),

    CONSTRAINT ck_partner_credential_kind CHECK (
        credential_kind IN ('API_KEY', 'HMAC_SECRET', 'WEBHOOK_SECRET')
    ),

    CONSTRAINT ck_partner_credential_status CHECK (
        status IN ('ACTIVE', 'ROTATED', 'REVOKED', 'EXPIRED')
    )
);

-- Hot-path lookups: "credentials of partner P" (list view) and "ACTIVE
-- credentials older than 11 months" (rotation scheduler sweep).
CREATE INDEX idx_partner_credential_partner
    ON partner_credential (partner_id, environment);

CREATE INDEX idx_partner_credential_rotation_sweep
    ON partner_credential (status, issued_at);
