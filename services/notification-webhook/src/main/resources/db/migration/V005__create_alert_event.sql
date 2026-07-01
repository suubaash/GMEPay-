-- notification-webhook service: operational alert ledger (WBS 8.6-T24).
--
-- Phase-1 alert sink: P2 alerts on (a) a webhook DLQ promotion and (b) the
-- pending-delivery backlog breaching its depth threshold are persisted here.
-- Future phases swap the WebhookAlertService transport for PagerDuty/Slack
-- behind the same method surface; the schema stays the durable record + dedup
-- substrate. partner_id is nullable because a queue-depth breach can be global
-- (not attributable to a single partner).
CREATE TABLE alert_event (
    id               BIGSERIAL    PRIMARY KEY,
    alert_type       VARCHAR(50)  NOT NULL,
    severity         VARCHAR(5)   NOT NULL,
    partner_id       BIGINT,
    message          TEXT         NOT NULL,
    context          TEXT,
    fired_at         TIMESTAMP    NOT NULL,
    acknowledged_at  TIMESTAMP,
    acknowledged_by  VARCHAR(100)
);

-- Dedup probe support: "is there an unacknowledged <type> alert for <partner>
-- fired after <cutoff>?" backs the 10-minute queue-depth alert-storm suppressor.
CREATE INDEX idx_alert_event_dedup
    ON alert_event (alert_type, partner_id, acknowledged_at, fired_at);
