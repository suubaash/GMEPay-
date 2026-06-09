-- transaction-mgmt: transactional outbox.
-- Written in the SAME DB transaction as the business state change so a domain event
-- is published iff the business change committed (atomic). A polling publisher reads
-- PENDING rows and dispatches them; see INTER_SERVICE_CONTRACTS.md for the rule that
-- this service owns its outbox.
--
-- Schema is intentionally H2/Postgres portable: TEXT payload (no JSONB), BIGSERIAL PK.

CREATE TABLE outbox (
    id            BIGSERIAL    PRIMARY KEY,
    aggregate_id  VARCHAR(64),
    event_type    VARCHAR(64),
    payload       TEXT,
    created_at    TIMESTAMP    DEFAULT CURRENT_TIMESTAMP,
    published_at  TIMESTAMP    NULL
);
