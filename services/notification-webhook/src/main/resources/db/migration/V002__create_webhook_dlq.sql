-- notification-webhook service: dead-letter queue for webhooks that have
-- exhausted the retry policy. Decoupled from webhook_delivery_log so Ops can
-- triage / replay independently.
CREATE TABLE webhook_dlq (
    id            BIGSERIAL    PRIMARY KEY,
    original_id   BIGINT       NOT NULL,
    webhook_id    VARCHAR(64)  NOT NULL,
    payload       TEXT         NOT NULL,
    reason        TEXT         NOT NULL,
    added_at      TIMESTAMP    NOT NULL
);

CREATE INDEX idx_webhook_dlq_webhook_id  ON webhook_dlq(webhook_id);
CREATE INDEX idx_webhook_dlq_original_id ON webhook_dlq(original_id);
