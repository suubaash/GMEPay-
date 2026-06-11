-- notification-webhook service: per-partner webhook endpoint registrations
-- (17.2-G11). Replaces the Phase-1 in-memory WebhookConfigStore with a durable
-- table behind the same port. The HMAC signing secret is NEVER stored here --
-- it is routed to Vault (see WebhookConfigEntry javadoc).
--
-- PostgreSQL-compatible SQL that also works under H2 PostgreSQL mode
-- (no JSONB, no Postgres-only types). event_types is a comma-separated list;
-- NULL means "subscribe to all events".
CREATE TABLE webhook_endpoint (
    id            BIGSERIAL     PRIMARY KEY,
    partner_id    BIGINT        NOT NULL,
    webhook_url   VARCHAR(512)  NOT NULL,
    event_types   TEXT,
    active        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP     NOT NULL,
    updated_at    TIMESTAMP     NOT NULL
);

CREATE INDEX idx_webhook_endpoint_partner_active ON webhook_endpoint(partner_id, active);
