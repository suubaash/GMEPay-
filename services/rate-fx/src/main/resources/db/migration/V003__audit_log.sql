-- V003: audit_log — the hash-chained, actor-attributed audit trail for rate-fx (gap T5-1).
--
-- WHY THIS TABLE EXISTS HERE
--
-- The CISO audit (§9, high-risk operations) recorded "FX rate change — NO: rate_snapshots permits
-- source='MANUAL' with no actor column". That is the finding stated precisely. rate_snapshots (V001)
-- holds snapshot_id, currency_code, usd_rate, source, effective_at, captured_at — and its CHECK
-- constraint explicitly allows source = 'MANUAL', i.e. a rate typed in by a human. There is nowhere
-- in that row to record WHICH human. And because resolution always reads the most recent effective
-- snapshot, one MANUAL row re-prices every subsequent quote and payment in that currency: it is a
-- pricing decision with money consequences and, until this migration, no attributable author.
--
-- rate_snapshots is deliberately NOT altered. Snapshots are immutable and effective-dated, several
-- resolvers read them on the hot path (CostRateResolver, SnapshotPartnerBQuotePort), and adding
-- actor columns there would still leave the reason and the before/after unrecorded and would not be
-- tamper-evident. The audit trail goes in its own hash-chained table and cross-references the
-- snapshot_id inside the payload.
--
-- SCHEMA PROVENANCE
--
-- Copied verbatim (modulo this comment) from libs/lib-audit/src/main/resources/db/audit/
-- V1__audit_log.sql, the canonical DDL for com.gme.pay.audit.DbAuditPublisher. Copied rather than
-- pulled in via an extra spring.flyway.locations entry so this module keeps ONE migration path and
-- one linear version history — two independently-versioned streams sharing a schema_history table is
-- a foot-gun the moment either side adds a migration.
--
-- V003 is the next free version: this module has V001__create_rate_snapshots.sql and
-- V002__create_rate_quotes.sql applied and immutable (checksum-stable), and nothing above them.
--
-- Portable to both targets with no vendor split: BIGSERIAL / BYTEA / TIMESTAMP behave identically on
-- PostgreSQL 16 and on H2 2.x in PostgreSQL mode, which is exactly what V001/V002 already rely on.
-- This module has no db/vendor/{h2,postgresql} directory and this plain CREATE TABLE needs none.

CREATE TABLE IF NOT EXISTS audit_log (
    -- BIGSERIAL surrogate. Append-only: never UPDATEd or DELETEd by application code.
    id              BIGSERIAL    NOT NULL,

    -- The aggregate kind being audited. rate-fx writes one: rate_snapshot.
    aggregate_type  VARCHAR(64)  NOT NULL,

    -- The row being audited. For rate_snapshot this is the CURRENCY CODE rather than the
    -- snapshot_id: a snapshot is written once and never changed, so a per-snapshot chain would be a
    -- chain of one and would prove nothing about the sequence. Keying on the currency makes the
    -- chain the pricing history of USD/<ccy>, which is what an investigator actually reads.
    aggregate_id    VARCHAR(64)  NOT NULL,

    -- Who made the change, in the com.gme.pay.audit.AuditActors vocabulary: a bare subject for an
    -- attested human, system:<component> / svc:<name> for a platform or service principal,
    -- unverified:<claim> for an unproven claim, or 'unattributed'. The bare literal 'system' is NOT
    -- writable (AuditEvent.newEvent rejects it) — see AuditActors for why.
    actor_id        VARCHAR(64)  NOT NULL,

    -- Client IP as seen by this service. NULL for the scheduler (off-request). VARCHAR(45) fits IPv6.
    actor_ip        VARCHAR(45),

    -- The verb (RATE_SNAPSHOT_MANUAL_OVERRIDE / _PARTNER_RECORDED / _LIVE_FETCHED). Distinct verbs
    -- per source so a hand-entered rate is distinguishable from a fetched one at a glance, without
    -- parsing the payload.
    event_type      VARCHAR(64)  NOT NULL,

    -- Before/after snapshots as raw JSON bytes (BYTEA on PG and H2 PostgreSQL-mode). The hash chain
    -- canonicalises over the raw bytes, so the writer emits them with a FIXED key order
    -- (com.gme.pay.ratefx.audit.CanonicalJson) rather than relying on Jackson defaults.
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
