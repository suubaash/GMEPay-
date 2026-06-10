-- payment-executor: idempotency_keys persists partner idempotency keys so a retried
-- POST /v1/payments replays the recorded outcome instead of re-executing (17.2-G08).
-- Uniqueness is scoped per partner — two partners may legitimately send the same key.
-- response_body holds the serialized response snapshot replayed on retry; TEXT keeps
-- H2/PostgreSQL parity (no JSONB), matching the outbox convention in transaction-mgmt.

CREATE TABLE idempotency_keys (
    id               BIGSERIAL     PRIMARY KEY,
    partner_id       BIGINT        NOT NULL,
    idempotency_key  VARCHAR(128)  NOT NULL,
    request_hash     VARCHAR(64)   NOT NULL,
    txn_ref          VARCHAR(64),
    response_status  VARCHAR(24),
    response_body    TEXT,
    created_at       TIMESTAMP     NOT NULL,
    expires_at       TIMESTAMP,
    CONSTRAINT uq_idempotency_partner_key UNIQUE (partner_id, idempotency_key)
);

CREATE INDEX idx_idempotency_expires ON idempotency_keys (expires_at);
