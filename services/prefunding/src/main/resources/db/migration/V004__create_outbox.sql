-- V004__create_outbox.sql
-- Transactional Outbox table for the prefunding service (same pattern as
-- revenue-ledger's V003__create_outbox.sql / transaction-mgmt's V002):
--   1. TierAlertEvaluator writes the balance_alert row AND an `outbox` row in the SAME
--      transaction as the balance mutation.
--   2. A scheduled OutboxPublisher polls unpublished rows and hands them to the
--      EventPublisher (LogEventPublisher locally; KafkaEventPublisher publishes event type
--      `prefunding.alert` to topic `gmepay.prefunding.alert` per ADR-001 topic naming).
--   3. On successful publish the row's published_at is stamped; failures leave it null so
--      the next tick retries (consumers must be idempotent — at-least-once).
--
-- Plain (non-partial) index on published_at so the migration stays portable to H2 in tests.
CREATE TABLE outbox (
    id            BIGSERIAL    PRIMARY KEY,
    aggregate_id  VARCHAR(64)  NOT NULL,
    event_type    VARCHAR(64)  NOT NULL,
    payload       TEXT         NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at  TIMESTAMP    NULL
);

CREATE INDEX idx_outbox_published_at ON outbox(published_at);
