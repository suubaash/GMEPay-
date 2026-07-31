-- V007: audit_log — the ADR-007 tamper-evident audit trail, for auth-identity.
--
-- WHY THIS EXISTS (gap T5-1 / CISO audit §9)
-- ------------------------------------------
-- auth-identity had NO audit trail of any kind. The CISO audit found that the three most
-- consequential things this service does emitted "not even a log line":
--
--   * authentication outcomes  — AuthVerificationService (partner HMAC) and JwtTokenService
--     (platform capability tokens). A brute-force against a partner API key, a replayed
--     nonce, a forged signature and a stolen-token probe were all invisible.
--   * RBAC grants               — RbacAdminService.createRole / grantPermission / assignRole /
--     createConstraint. Granting the `*` super-permission to a principal left no trace at
--     all, so "who gave this operator refund authority, and when" had no answer.
--   * credential lifecycle      — ApiKeyIssuanceService issue / rotate / revoke. Partner
--     machine credentials appeared and disappeared with no record of who minted them.
--
-- SCHEMA PROVENANCE
-- -----------------
-- This is a verbatim copy of libs/lib-audit/src/main/resources/db/audit/V1__audit_log.sql
-- (including the chain_version column and its CHECK). It is COPIED rather than mounted via
-- a second `spring.flyway.locations` entry because this service ships exactly one migration
-- location (classpath:db/migration, the Boot default — there is no db/vendor split here) and
-- adding a second location changes the Flyway contract for every existing deployment of this
-- service. The DDL is intentionally identical: DbAuditPublisher's INSERT/SELECT statements
-- are shared code and would break on any divergence, and a regulator sweeping audit_log
-- across services must see one shape.
--
-- If lib-audit's DDL changes, this file must be re-synced by a NEW migration (V008+) — never
-- by editing this one. AuditLogSchemaParityTest pins the column set so the divergence is
-- caught by the build rather than by a failed verification sweep.
--
-- PostgreSQL-compatible; runs identically under H2 2.x in PostgreSQL mode (BIGSERIAL/BYTEA
-- are the portable spellings the rest of the platform's audit tables use).

CREATE TABLE IF NOT EXISTS audit_log (
    -- BIGSERIAL surrogate. Append-only: never UPDATEd or DELETEd by application code.
    id              BIGSERIAL    NOT NULL,

    -- The aggregate kind being audited, lower_snake_case. In this service:
    --   auth_session       partner HMAC request-signature verification outcomes
    --   auth_token         platform capability token issue / verify outcomes
    --   api_key            one partner machine credential, keyed by its PUBLIC key id
    --   api_key_principal  a (partnerCode, environment) principal's rotation events
    --   rbac_permission    the permission catalogue
    --   rbac_role          one role's authority (creation + permission grant/revoke)
    --   rbac_principal     one principal's role assignments
    --   rbac_constraint    typed constraints attached to a scope
    aggregate_type  VARCHAR(64)  NOT NULL,

    -- The row being audited. VARCHAR accommodates both string and numeric natural keys.
    -- The hash chain is per (aggregate_type, aggregate_id).
    aggregate_id    VARCHAR(64)  NOT NULL,

    -- Who acted, in the com.gme.pay.audit.AuditActors vocabulary. NOT free text: the bare
    -- literal 'system' and blanks are rejected at the write choke point (AuditEvent.newEvent),
    -- and an unproven claim is recorded as 'unverified:<claim>' so it can never be read as, or
    -- joined against, a real operator id. A failed login has no verified subject, which is
    -- exactly what that namespace is for.
    actor_id        VARCHAR(64)  NOT NULL,

    -- Transport-level peer address of the caller. VARCHAR(45) fits IPv6.
    actor_ip        VARCHAR(45),

    -- The verb (AUTH_VERIFY_FAILED, TOKEN_ISSUED, RBAC_PERMISSION_GRANTED, API_KEY_REVOKED …).
    -- See com.gme.pay.auth.audit.AuthAuditEvents for the closed set this service writes.
    event_type      VARCHAR(64)  NOT NULL,

    -- Before/after row snapshots as raw JSON bytes (BYTEA on PG and H2 PostgreSQL-mode).
    -- The hash chain canonicalises over the raw bytes.
    --
    -- NEVER put credential material here. Secrets, API-key secrets, HMAC signatures,
    -- passwords and raw tokens are omitted or reduced to a truncated SHA-256 fingerprint
    -- (AuditPayload.fingerprint) — an audit table is a long-lived, widely-readable, exported
    -- artefact and is the worst possible place to durably store a live credential.
    before_jsonb    BYTEA,
    after_jsonb     BYTEA,

    -- 32-byte SHA-256 outputs.
    -- prev_hash = prior row's row_hash for this (aggregate_type, aggregate_id),
    --             or the 32-zero genesis vector for the first row of an aggregate.
    -- row_hash  = SHA-256(prev_hash || canonicalised(event)); see lib-audit/HashChain.
    prev_hash       BYTEA        NOT NULL,
    row_hash        BYTEA        NOT NULL,

    -- Application sets recorded_at explicitly so the same value goes into the hash and into
    -- the stored column. DEFAULT CURRENT_TIMESTAMP is a safety net only.
    -- Plain TIMESTAMP (not TIMESTAMPTZ): H2 PostgreSQL-mode compatibility.
    recorded_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Which digest sealed this row. 1 = the original five-field digest, which left
    -- aggregate_type/aggregate_id/actor_ip OUTSIDE the hash and therefore rewritable in
    -- place. 2 = the current digest, which seals those three plus the version number itself.
    -- Every writer sets this explicitly; this table starts empty so in practice every row
    -- here is v2, and AuditChainVerifier still reports the v1 count rather than assuming.
    chain_version   SMALLINT     NOT NULL DEFAULT 1,

    CONSTRAINT pk_audit_log PRIMARY KEY (id),

    CONSTRAINT chk_audit_log_chain_version CHECK (chain_version IN (1, 2))
);

-- Per-aggregate index: chain verification walks id-ascending for (aggregate_type, aggregate_id).
CREATE INDEX IF NOT EXISTS idx_audit_log_aggregate
    ON audit_log (aggregate_type, aggregate_id, id);

-- Recent-activity index: descending recorded_at scan.
CREATE INDEX IF NOT EXISTS idx_audit_log_recorded_at
    ON audit_log (recorded_at DESC);

-- Attribution-quality sweep: "how many of our audited actions were never attributed to a
-- proven identity" is a number a regulator asks for and a number this platform should be able
-- to answer without a table scan. Supports
--   SELECT count(*) FROM audit_log WHERE actor_id LIKE 'unverified:%' OR actor_id = 'unattributed';
CREATE INDEX IF NOT EXISTS idx_audit_log_actor
    ON audit_log (actor_id);
