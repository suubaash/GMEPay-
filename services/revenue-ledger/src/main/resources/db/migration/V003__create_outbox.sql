-- V003__create_outbox.sql
-- Creates the transactional Outbox table for the revenue-ledger service.
--
-- The Outbox pattern guarantees at-least-once event delivery WITHOUT 2PC:
--   1. JpaJournalStore.save(Journal) writes the journal AND an `outbox` row in the SAME transaction.
--   2. A scheduled OutboxPublisher polls unpublished rows and hands them to the EventPublisher
--      (LogEventPublisher in Phase 1, Kafka producer at integration phase).
--   3. On successful publish the row's published_at is stamped; failures leave it null so the
--      next tick retries (idempotent at the consumer).
--
-- Compatible with PostgreSQL and H2 PostgreSQL mode. We intentionally use a plain (non-partial)
-- index on published_at so the migration is portable to H2 in tests — production PG can later
-- swap to a partial index (WHERE published_at IS NULL) for storage efficiency without changing
-- any code (the JPQL query is "publishedAt is null", which uses either flavour fine).

CREATE TABLE outbox (
    id            BIGSERIAL    PRIMARY KEY,
    aggregate_id  VARCHAR(64)  NOT NULL,
    event_type    VARCHAR(64)  NOT NULL,
    payload       TEXT         NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at  TIMESTAMP    NULL
);

CREATE INDEX idx_outbox_published_at ON outbox(published_at);
