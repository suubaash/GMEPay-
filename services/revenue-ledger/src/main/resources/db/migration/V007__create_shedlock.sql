-- V007 — ShedLock table for distributed scheduler locking (gap T3-11 defect 3, the named follow-up).
--
-- revenue-ledger was the LAST service in the fleet with unlocked @Scheduled work. Its one job,
-- OutboxPublisher#publishPending, ticks every second: it selects unpublished outbox rows, publishes
-- each to the EventPublisher (Kafka in a real deployment), and only then stamps published_at. Two
-- replicas ticking a second apart therefore select the same rows and publish them twice, so every
-- downstream consumer of a revenue-ledger event sees it once per replica.
--
-- Canonical ShedLock JdbcTemplate schema — column names and types are fixed by the provider:
--   name       -- lock name (the @SchedulerLock name), PK: one row per named job
--   lock_until -- held until this instant; a tick may run only when now >= lock_until
--   locked_at  -- when the current holder acquired it
--   locked_by  -- holder identity (hostname), for diagnostics only
--
-- Engine-neutral (PostgreSQL + H2 in PostgreSQL mode): plain TIMESTAMP + VARCHAR, no vendor types.
-- This module has no db/vendor/{h2,postgresql} overlay (only config-registry does), so there is
-- nothing to mirror — one file is the whole change.
--
-- Additive only — a new table, touching nothing in V001–V006. Identical in shape to
-- payment-executor's V011, settlement-reconciliation's V014, notification-webhook's V008,
-- scheme-adapter-zeropay's V005, prefunding's V008 and transaction-mgmt's V010, so the services
-- cannot drift apart.

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
