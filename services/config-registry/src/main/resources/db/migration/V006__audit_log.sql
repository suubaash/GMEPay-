-- V006: audit_log table — Slice 1 (1B.3) implementation of ADR-007 tier 1 (hot DB).
--
-- ADR-007 prescribes three tiers:
--   1. Hot — dedicated `audit` PostgreSQL DB with INSERT-only role + hash chain.
--   2. Stream — Kafka topic `gmepay.audit.<aggregateType>`.
--   3. Cold — MinIO bucket `gmepay-audit-archive` (object-lock + 10yr retention).
--
-- Slice 1 lands tiers 1 + 2 only, and keeps tier 1's table inside the config-registry
-- DB rather than provisioning a separate `audit` logical DB. The dedicated DB + the
-- INSERT-only role come in Slice 8 hardening — the Slice 1 trade-off is documented in
-- docs/PARTNER_SETUP_PLAN.md §"Slice 1": we want the hash chain in operators' hands now
-- and a logical-DB split is an ops change (new Helm chart, new credential set) that
-- doesn't add forensic value beyond what the chain itself already provides.
--
-- Migrating into the dedicated audit DB later is a non-destructive logical move —
-- the schema is intentionally self-contained (no FKs out, no triggers, no application-
-- specific defaults) so the audit DB can absorb this table without translation.
--
-- PostgreSQL-compatible; runs identically under H2 in PostgreSQL mode.
-- Per ADR-013 Expand discipline: this is a wholly-new table, no in-place ALTER NOT
-- NULL on existing columns.

CREATE TABLE audit_log (
    -- BIGSERIAL surrogate. Append-only: never UPDATEd by application code (UPDATEs
    -- to this table are revoked at the role level in Slice 8 — until then the
    -- discipline lives in the application layer alone).
    id              BIGSERIAL    NOT NULL,

    -- The aggregate kind being audited. Drives the Kafka topic name
    -- (`gmepay.audit.<aggregate_type>`) — keep this short and lower_snake_case.
    -- Slice 1 only uses `partner`; Slice 2 adds `partner_contact`, etc.
    aggregate_type  VARCHAR(64)  NOT NULL,

    -- The row being audited. Stored as VARCHAR so we can audit aggregates whose
    -- natural key is a string (e.g. partner_code during the Expand phase) and
    -- aggregates whose key is BIGINT (the surrogate id once Contract runs) using
    -- the same column. The hash chain is per (aggregate_type, aggregate_id).
    aggregate_id    VARCHAR(64)  NOT NULL,

    -- Who made the change. For 4-eyes (ADR-008): the proposer on PROPOSED, the
    -- approver on APPLIED. The literal 'system' is reserved for system-driven
    -- changes (e.g. auto-suspend on prefunding breach) and is honoured by the
    -- 4-eyes CHECK constraint as the explicit carve-out per ADR-008.
    actor_id        VARCHAR(64)  NOT NULL,

    -- Client IP at the BFF (PROXY-protocol-aware). NULL for system events.
    -- VARCHAR(45) fits an IPv6 textual address with no embedded port.
    actor_ip        VARCHAR(45),

    -- The verb. Slice 1 emits 'PARTNER_SAVED'; Slice 8 adds the FSM verbs
    -- (PROPOSED / APPROVED / APPLIED / REJECTED / SUSPENDED / etc).
    event_type      VARCHAR(64)  NOT NULL,

    -- Before/after row snapshots as JSON bytes. JSONB on PostgreSQL for queryability;
    -- under H2-in-PostgreSQL-mode this is stored as JSON (H2 lacks a native JSONB
    -- column type but accepts the same DDL when you spell it lower-case).
    before_jsonb    JSONB,
    after_jsonb     JSONB,

    -- 32-byte SHA-256 outputs. prev_hash references the prior row of the SAME
    -- (aggregate_type, aggregate_id) — the chain is per-aggregate, not per-table.
    -- prev_hash for the first row of an aggregate is the 32-byte zero genesis vector
    -- (NOT NULL — we want every row to be explicit about whether it starts a chain).
    -- row_hash = SHA-256(prev_hash || canonicalised(event)); see lib-audit/HashChain.
    prev_hash       BYTEA        NOT NULL,
    row_hash        BYTEA        NOT NULL,

    -- DEFAULT now() is a safety net; the application sets recorded_at explicitly so
    -- the value going into the hash is the same value the row stores (DB's now()
    -- fires at INSERT, which is AFTER the hash was computed at the application
    -- layer — without explicit-set those two would diverge and the chain would fail
    -- on verification).
    -- Plain TIMESTAMP (not TIMESTAMPTZ): H2 PostgreSQL-mode does not recognise the
    -- TIMESTAMPTZ alias — same portable spelling rationale as V004.
    recorded_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_audit_log PRIMARY KEY (id),

    -- Sanity-check hash sizes. Bytea length checks would cost essentially nothing at
    -- INSERT (we're comparing to 32) but they catch a corrupted writer immediately
    -- rather than at the next chain verify.
    CONSTRAINT chk_audit_log_prev_hash_len CHECK (octet_length(prev_hash) = 32),
    CONSTRAINT chk_audit_log_row_hash_len  CHECK (octet_length(row_hash)  = 32)
);

-- Verification reads the chain in id order for a given (aggregate_type, aggregate_id);
-- the per-aggregate composite index keeps that walk cheap once the table grows.
CREATE INDEX idx_audit_log_aggregate
    ON audit_log (aggregate_type, aggregate_id, id);

-- Recent-activity views ("show me the last 50 audit rows across all aggregates")
-- want a recorded_at-descending scan; this covers it without a sort.
CREATE INDEX idx_audit_log_recorded_at
    ON audit_log (recorded_at DESC);
