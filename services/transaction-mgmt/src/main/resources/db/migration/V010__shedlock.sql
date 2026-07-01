-- V010 — ShedLock table for distributed scheduler locking (#3).
--
-- Backs net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider so the service's
-- @Scheduled methods (expiry sweeper, stuck-txn alert sweeper, outbox drain) fire on at most ONE
-- replica per tick instead of double-firing across horizontally-scaled instances. One row per named
-- lock; ShedLock upserts/reads it (locked_at..lock_until window + locked_by owner) each tick.
--
-- Canonical ShedLock JdbcTemplate schema. Engine-neutral (PostgreSQL + H2 PG-mode): the standard
-- ShedLock DDL uses TIMESTAMP + VARCHAR, both portable. Additive only — new migration, no in-place
-- edit of an existing one.

CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
