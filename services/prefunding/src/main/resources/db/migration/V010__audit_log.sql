-- V010: audit_log — the hash-chained, actor-attributed audit trail for prefunding (gap T5-1).
--
-- WHY THIS TABLE EXISTS HERE
--
-- The CISO audit (§9, high-risk operations) recorded "Prefunding balance movement — NO:
-- ledger_entry (V002) has no actor, no reason, no IP column". That is exactly right: ledger_entry
-- is a *money* ledger — partner_id, txn_ref, entry_type, amount, currency, created_at — and it was
-- doing double duty as the audit trail it was never designed to be. It can tell you that USD
-- 40,000 was credited to SENDMN at 03:14; it cannot tell you WHO credited it, on what authority,
-- from where, or why. An unexplained prefunding credit is precisely the event a regulator asks to
-- see attributed, and the honest answer was "we don't record that".
--
-- ledger_entry is deliberately NOT altered. It is append-only, it is joined by the movements /
-- deductions read surfaces and by transaction-mgmt's ledger references, and bolting actor columns
-- onto it would conflate two different retention and access stories (a money ledger the finance
-- team reads vs. an audit trail a regulator reads). The audit trail goes in its own table with its
-- own hash chain, and the two are cross-referenced by ledger_entry.id inside the audit payload.
--
-- SCHEMA PROVENANCE
--
-- Copied verbatim (modulo this comment) from libs/lib-audit/src/main/resources/db/audit/
-- V1__audit_log.sql, which is the canonical DDL for com.gme.pay.audit.DbAuditPublisher. It is
-- copied rather than referenced through an extra spring.flyway.locations entry so this module keeps
-- ONE migration path and one linear version history — adding a second location would put two
-- independently-versioned streams in the same schema_history table, which is a foot-gun the moment
-- either side adds a migration.
--
-- V010 is the next free version: V001..V009 are applied and immutable (checksum-stable).
--
-- Portable to both targets with no vendor split: BIGSERIAL / BYTEA / TIMESTAMP are the spellings
-- this module already uses in V001..V009 and they run identically on PostgreSQL 16 and on H2 2.x in
-- PostgreSQL mode. There is no db/vendor/{h2,postgresql} directory in this module, and this plain
-- CREATE TABLE does not need one.

CREATE TABLE IF NOT EXISTS audit_log (
    -- BIGSERIAL surrogate. Append-only: never UPDATEd or DELETEd by application code.
    id              BIGSERIAL    NOT NULL,

    -- The aggregate kind being audited. Keep this short and lower_snake_case.
    -- prefunding writes three: partner_balance, partner_limit, partner_aml_usage.
    aggregate_type  VARCHAR(64)  NOT NULL,

    -- The row being audited. For all three prefunding aggregates this is the partner code, so one
    -- partner's float history is one chain.
    aggregate_id    VARCHAR(64)  NOT NULL,

    -- Who made the change, in the com.gme.pay.audit.AuditActors vocabulary: a bare subject for an
    -- attested human, system:<component> / svc:<name> for a platform or service principal,
    -- unverified:<claim> for an unproven claim, or 'unattributed'. The bare literal 'system' is
    -- NOT writable (AuditEvent.newEvent rejects it) — see AuditActors for why.
    actor_id        VARCHAR(64)  NOT NULL,

    -- Client IP as seen by this service. NULL for off-request (system) events. VARCHAR(45) fits IPv6.
    actor_ip        VARCHAR(45),

    -- The verb (e.g. BALANCE_DEBITED, CREDIT_LIMIT_SET, BREACH_SUSPENSION_PROPOSED).
    event_type      VARCHAR(64)  NOT NULL,

    -- Before/after row snapshots as raw JSON bytes (BYTEA on PG and H2 PostgreSQL-mode).
    -- The hash chain canonicalises over the raw bytes, so the writer emits them with a FIXED key
    -- order (com.gme.pay.prefunding.audit.CanonicalJson) rather than relying on Jackson defaults.
    before_jsonb    BYTEA,
    after_jsonb     BYTEA,

    -- 32-byte SHA-256 outputs.
    -- prev_hash = prior row's row_hash for this (aggregate_type, aggregate_id),
    --             or the 32-zero genesis vector for the first row of an aggregate.
    -- row_hash  = SHA-256(prev_hash || canonicalised(event)); see lib-audit/HashChain.
    prev_hash       BYTEA        NOT NULL,
    row_hash        BYTEA        NOT NULL,

    -- Application sets recorded_at explicitly so the same value goes into the hash and into the
    -- stored column. DEFAULT CURRENT_TIMESTAMP is a safety net only.
    -- Plain TIMESTAMP (not TIMESTAMPTZ): H2 PostgreSQL-mode compatibility.
    recorded_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Which digest sealed this row (gap T5-1). 1 = the original five-field digest, which left
    -- aggregate_type, aggregate_id and actor_ip OUTSIDE the hash and therefore rewritable in place.
    -- 2 = the current digest, which seals those three plus the version number itself so a v2 row
    -- cannot be downgraded to v1 by editing this column. This table starts empty, so every row in
    -- it is v2; the column and its DEFAULT exist so the shared verifier can walk it unchanged.
    chain_version   SMALLINT     NOT NULL DEFAULT 1,

    CONSTRAINT pk_audit_log PRIMARY KEY (id),

    -- Reject a version this build cannot canonicalise at INSERT rather than at the next
    -- verification sweep (where it would surface as "unverifiable", i.e. as suspicion).
    CONSTRAINT chk_audit_log_chain_version CHECK (chain_version IN (1, 2))
);

-- Per-aggregate index: chain verification walks id-ascending for (aggregate_type, aggregate_id).
CREATE INDEX IF NOT EXISTS idx_audit_log_aggregate
    ON audit_log (aggregate_type, aggregate_id, id);

-- Recent-activity index: descending recorded_at scan.
CREATE INDEX IF NOT EXISTS idx_audit_log_recorded_at
    ON audit_log (recorded_at DESC);
