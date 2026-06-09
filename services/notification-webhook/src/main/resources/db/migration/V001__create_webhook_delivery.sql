-- notification-webhook service: webhook delivery log (Phase-1 persistence).
-- Records every dispatch attempt with status / payload / error so the retry
-- and DLQ pipeline has a durable audit trail.
--
-- PostgreSQL-compatible SQL that also works under H2 PostgreSQL mode
-- (no JSONB, no Postgres-only types).
CREATE TABLE webhook_delivery_log (
    id                  BIGSERIAL    PRIMARY KEY,
    webhook_id          VARCHAR(64)  NOT NULL,
    event_type          VARCHAR(64)  NOT NULL,
    payload             TEXT         NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    attempt             INT          NOT NULL DEFAULT 0,
    last_error          TEXT,
    last_attempted_at   TIMESTAMP,
    delivered_at        TIMESTAMP,
    created_at          TIMESTAMP    NOT NULL
);

CREATE INDEX idx_webhook_delivery_log_webhook_id ON webhook_delivery_log(webhook_id);
CREATE INDEX idx_webhook_delivery_log_status     ON webhook_delivery_log(status);
