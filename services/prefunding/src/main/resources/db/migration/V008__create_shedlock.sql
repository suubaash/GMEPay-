-- V008__create_shedlock.sql
-- Distributed scheduler lock table for ShedLock (net.javacrumbs.shedlock).
--
-- Why: the @Scheduled OutboxPublisher.publishPending() drain runs on EVERY instance.
-- With more than one prefunding replica, two ticks could read the same unpublished
-- rows and double-publish before either stamps published_at. ShedLock's
-- JdbcTemplateLockProvider serialises the tick across instances on this single row per
-- lock name, so exactly one replica drains per interval (the others skip the tick).
--
-- Canonical ShedLock JDBC schema (column names/types are fixed by the provider):
--   name       -- lock name (@SchedulerLock name), PK
--   lock_until -- lock is held until this instant; a tick may run only if now >= lock_until
--   locked_at  -- when the current holder acquired it
--   locked_by  -- holder identity (hostname), for diagnostics
--
-- Additive-only: new table, touches nothing in V001–V007. Portable to H2 (tests) and
-- PostgreSQL (prod) — plain TIMESTAMP, no vendor-specific types.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
