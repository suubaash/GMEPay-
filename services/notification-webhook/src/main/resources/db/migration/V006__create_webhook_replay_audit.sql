-- notification-webhook service: operator webhook-replay audit ledger (Ops wave).
--
-- Every operator-initiated replay of a delivery (typically a DLQ'd / failed row
-- re-enqueued for re-send) records who requested it, why, and the outcome. This
-- is the durable audit trail for the manual-replay surface
-- (POST /v1/webhooks/deliveries/{id}/replay). Additive to the existing delivery
-- store; it never mutates delivery rows itself — the service flips the row back
-- to PENDING and writes one audit row per accepted request.
CREATE TABLE webhook_replay_audit (
    id            BIGSERIAL    PRIMARY KEY,
    delivery_id   BIGINT       NOT NULL,
    webhook_id    VARCHAR(64)  NOT NULL,
    requested_by  VARCHAR(100) NOT NULL,
    reason        TEXT,
    outcome       VARCHAR(24)  NOT NULL,
    requested_at  TIMESTAMP    NOT NULL
);

-- Trace all replays of a given delivery row (Ops audit view).
CREATE INDEX idx_webhook_replay_audit_delivery
    ON webhook_replay_audit (delivery_id, requested_at);
