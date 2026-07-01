-- V038: Operations kill-switch state (Ops-features wave — global pause /
-- maintenance mode + emergency entity suspend/quarantine).
--
-- WHY
-- ---
-- The ops-console needs a durable, single source of truth for "what is the
-- platform allowed to do right now" — the read model exposed by the shared
-- lib-api-contracts OperationalStatusView. Two shapes of control:
--
--   * GLOBAL flags — system_paused (hard master kill switch) and
--     maintenance_mode (soft, degraded/read-mostly). These live in a single
--     singleton row (id = 1) in ops_control so a read is one PK lookup and a
--     write is one UPDATE — no aggregation needed for the global state.
--   * PER-ENTITY suspensions — a partner / scheme / route quarantined
--     individually. Modelled as rows in ops_suspension; the operational-status
--     read aggregates the ACTIVE ones into suspendedPartners/Schemes/Routes.
--
-- Emergency actions are single-operator for speed (NOT 4-eyes gated) — the
-- point of a kill switch is that one operator can pull it now. Every action is
-- still hash-chain audited (ADR-007) with operator + reason, so the who/what/
-- when/why is regulator-defensible after the fact.
--
-- DDL DISCIPLINE
-- --------------
-- Plain portable DDL (TIMESTAMP, VARCHAR, BOOLEAN) — no TIMESTAMPTZ / JSONB
-- (ADR rule). PostgreSQL and H2 (PostgreSQL mode) both honour every statement.
-- Additive-only: new tables, no ALTER of an applied migration.

-- Global singleton control row. The CHECK pins it to a single row (id = 1) so
-- there is exactly one authoritative global state; the service upserts it.
CREATE TABLE ops_control (
    id              INTEGER      NOT NULL PRIMARY KEY,
    system_paused   BOOLEAN      NOT NULL DEFAULT FALSE,
    maintenance_mode BOOLEAN     NOT NULL DEFAULT FALSE,
    reason          VARCHAR(500),
    since           TIMESTAMP,
    updated_by      VARCHAR(100),
    updated_at      TIMESTAMP,
    CONSTRAINT ck_ops_control_singleton CHECK (id = 1)
);

-- Seed the all-clear singleton so a read never has to cope with an absent row.
INSERT INTO ops_control (id, system_paused, maintenance_mode) VALUES (1, FALSE, FALSE);

-- Per-entity emergency suspensions. entity_type is one of the shared
-- OperationalStatusView buckets (PARTNER / SCHEME / ROUTE); entity_id is the
-- natural reference (partner code, scheme id, route identifier). A suspension
-- is logically toggled via `active` rather than deleted, so the audit story and
-- history survive an unsuspend.
CREATE TABLE ops_suspension (
    id            BIGSERIAL     NOT NULL PRIMARY KEY,
    entity_type   VARCHAR(16)   NOT NULL,
    entity_id     VARCHAR(200)  NOT NULL,
    reason        VARCHAR(500),
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_by    VARCHAR(100),
    created_at    TIMESTAMP     NOT NULL,
    CONSTRAINT ck_ops_suspension_entity_type CHECK (
        entity_type IN ('PARTNER', 'SCHEME', 'ROUTE'))
);

-- One ACTIVE suspension per (type, id) — a second suspend of an already-active
-- entity is idempotent (the service reactivates/updates the existing row rather
-- than inserting a duplicate). A partial unique index would be ideal but is not
-- portable to H2; the service enforces the invariant with a lookup-then-write.
CREATE INDEX ix_ops_suspension_active
    ON ops_suspension (entity_type, entity_id, active);
