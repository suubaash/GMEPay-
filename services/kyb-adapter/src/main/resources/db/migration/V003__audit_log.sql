-- V003: audit_log — the hash-chained, actor-attributed audit trail for kyb-adapter (gaps T5-1, T5-3).
--
-- WHY THIS TABLE EXISTS HERE
--
-- V004 adds transaction_screening: a per-transaction record of what was screened, by whom, and what
-- the payment path did about it. That table is EVIDENCE, and evidence with no tamper-evidence is a
-- claim. A regulator asking "show me that this payment's counterparties were screened" is entitled to
-- ask the follow-up — "and how do you know this row was not written, or rewritten, afterwards" — and
-- until this migration kyb-adapter had no answer at all: T5-1 closed the audit-coverage gap for
-- config-registry, auth-identity, prefunding and rate-fx, and explicitly listed kyb-adapter as one of
-- the services still uninstrumented.
--
-- transaction_screening is deliberately NOT given actor/hash columns of its own. It is the operational
-- record the payment path reads back; the audit trail is a separate append-only story with a different
-- retention and access profile, and conflating the two is what left ledger_entry doing double duty in
-- prefunding (see that module's V010 header for the same argument at length). The two are
-- cross-referenced by txn_ref inside the audit payload.
--
-- SCHEMA PROVENANCE
--
-- Copied from libs/lib-audit/src/main/resources/db/audit/V1__audit_log.sql, which is the canonical DDL
-- for com.gme.pay.audit.DbAuditPublisher, via services/prefunding/.../V010__audit_log.sql (the same
-- copy, already adapted for a module with no db/vendor split). It is copied rather than referenced
-- through an extra spring.flyway.locations entry so this module keeps ONE migration path and one linear
-- version history — a second location would put two independently-versioned streams in the same
-- schema_history table.
--
-- V003 is the next free version: V001 and V002 are applied and immutable (checksum-stable).
--
-- Portable to both targets with no vendor split: BIGSERIAL / BYTEA / TIMESTAMP are the spellings this
-- module already uses in V001..V002 and they run identically on PostgreSQL 16 and on H2 2.x in
-- PostgreSQL mode. There is no db/vendor/{h2,postgresql} directory in this module.

CREATE TABLE IF NOT EXISTS audit_log (
    -- BIGSERIAL surrogate. Append-only: never UPDATEd or DELETEd by application code.
    id              BIGSERIAL    NOT NULL,

    -- The aggregate kind being audited. Keep this short and lower_snake_case.
    -- kyb-adapter writes one: transaction_screening.
    aggregate_type  VARCHAR(64)  NOT NULL,

    -- The row being audited. For transaction_screening this is the TRANSACTION reference, so one
    -- transaction's screening history is one chain — which is the unit a regulator asks about.
    aggregate_id    VARCHAR(64)  NOT NULL,

    -- Who made the change, in the com.gme.pay.audit.AuditActors vocabulary: a bare subject for an
    -- attested human, system:<component> / svc:<name> for a platform or service principal,
    -- unverified:<claim> for an unproven claim, or 'unattributed'. The bare literal 'system' is
    -- NOT writable (AuditEvent.newEvent rejects it) — see AuditActors for why.
    actor_id        VARCHAR(64)  NOT NULL,

    -- Client IP as seen by this service. NULL for off-request (system) events. VARCHAR(45) fits IPv6.
    actor_ip        VARCHAR(45),

    -- The verb (e.g. TRANSACTION_SCREENED, TRANSACTION_SCREENING_REFUSED).
    event_type      VARCHAR(64)  NOT NULL,

    -- Before/after snapshots as raw JSON bytes (BYTEA on PG and H2 PostgreSQL-mode). The hash chain
    -- canonicalises over the raw bytes, so the writer emits them with a FIXED key order
    -- (com.gme.pay.kybadapter.audit.CanonicalJson) rather than relying on Jackson defaults.
    --
    -- NOTE for this module specifically: these payloads carry NO subject PII. A screening subject's
    -- name and date of birth are exactly the columns this platform does not encrypt at rest (gap
    -- T5-5), so the payload records WHICH attributes were present, never their values — see
    -- PaymentScreeningSubject.attributeSummary(). An audit trail that leaked the payer roster would be
    -- a new PII store created by a control meant to reduce risk.
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
    -- cannot be downgraded to v1 by editing this column. This table starts empty, so every row in it
    -- is v2; the column and its DEFAULT exist so the shared verifier can walk it unchanged.
    chain_version   SMALLINT     NOT NULL DEFAULT 1,

    CONSTRAINT pk_audit_log PRIMARY KEY (id),

    -- Reject a version this build cannot canonicalise at INSERT rather than at the next verification
    -- sweep (where it would surface as "unverifiable", i.e. as suspicion).
    CONSTRAINT chk_audit_log_chain_version CHECK (chain_version IN (1, 2))
);

-- Per-aggregate index: chain verification walks id-ascending for (aggregate_type, aggregate_id).
CREATE INDEX IF NOT EXISTS idx_audit_log_aggregate
    ON audit_log (aggregate_type, aggregate_id, id);

-- Recent-activity index: descending recorded_at scan.
CREATE INDEX IF NOT EXISTS idx_audit_log_recorded_at
    ON audit_log (recorded_at DESC);
