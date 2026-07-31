-- lib-audit V0001: audit_log table — durable hash-chain per ADR-007 tier 1 (hot DB).
--
-- This migration is provided by lib-audit so any service that adopts DbAuditPublisher
-- can wire in the table without copying DDL. Services that already have their own
-- audit_log table (e.g. config-registry V006) should configure Flyway to exclude this
-- migration via flyway.locations or baseline on install, or simply not add the
-- lib-audit migration path — both approaches are documented in PARTNER_SETUP_PLAN.md.
--
-- The version number V0001 (4-digit prefix) is intentionally in a different namespace
-- from service-local migrations (V001..V999) so the two can coexist when a service
-- opts in to multiple migration paths:
--   spring.flyway.locations=classpath:db/migration,classpath:db/migration/audit
-- See DbAuditPublisher's class-level javadoc for the recommended wiring.
--
-- PostgreSQL-compatible; runs identically under H2 2.x in PostgreSQL mode.
-- Uses BIGSERIAL / BYTEA — the same portable spellings as config-registry V006.

CREATE TABLE IF NOT EXISTS audit_log (
    -- BIGSERIAL surrogate. Append-only: never UPDATEd or DELETEd by application code.
    id              BIGSERIAL    NOT NULL,

    -- The aggregate kind being audited. Drives the Kafka topic name
    -- (gmepay.audit.<aggregate_type>). Keep this short and lower_snake_case.
    aggregate_type  VARCHAR(64)  NOT NULL,

    -- The row being audited. VARCHAR accommodates both string and numeric natural keys.
    -- The hash chain is per (aggregate_type, aggregate_id).
    aggregate_id    VARCHAR(64)  NOT NULL,

    -- Who made the change. System-driven events use the literal 'system'.
    actor_id        VARCHAR(64)  NOT NULL,

    -- Client IP at the BFF. NULL for system events. VARCHAR(45) fits IPv6.
    actor_ip        VARCHAR(45),

    -- The verb (e.g. PARTNER_SAVED, PROPOSED, APPROVED, APPLIED, REJECTED, SUSPENDED).
    event_type      VARCHAR(64)  NOT NULL,

    -- Before/after row snapshots as raw JSON bytes (BYTEA on PG and H2 PostgreSQL-mode).
    -- The hash chain canonicalises over the raw bytes.
    before_jsonb    BYTEA,
    after_jsonb     BYTEA,

    -- 32-byte SHA-256 outputs.
    -- prev_hash = prior row's row_hash for this (aggregate_type, aggregate_id),
    --             or the 32-zero genesis vector for the first row of an aggregate.
    -- row_hash  = SHA-256(prev_hash || canonicalised(event)); see lib-audit/HashChain.
    prev_hash       BYTEA        NOT NULL,
    row_hash        BYTEA        NOT NULL,

    -- Application sets recorded_at explicitly so the same value goes into the hash
    -- and into the stored column. DEFAULT CURRENT_TIMESTAMP is a safety net only.
    -- Plain TIMESTAMP (not TIMESTAMPTZ): H2 PostgreSQL-mode compatibility.
    recorded_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Which digest sealed this row (gap T5-1). 1 = the original five-field digest
    -- (event_type | actor_id | recorded_at | before | after), which left aggregate_type,
    -- aggregate_id and actor_ip OUTSIDE the hash and therefore rewritable in place.
    -- 2 = the current digest, which seals those three plus the version number itself so a
    -- v2 row cannot be downgraded to v2-verifies-as-v1 by editing this column.
    -- DEFAULT 1 is deliberate and applies only to rows that pre-date the column: every
    -- writer sets this explicitly, so a row arriving with the default is a row written by
    -- code that does not know about chain versions and must be verified under v1.
    -- See libs/lib-audit/HashChain for why historical rows are NOT re-sealed.
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
