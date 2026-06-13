-- V030: partner_webhook_subscription — Slice 8 Lane D "Webhook endpoint
-- wire-up at partner activation" (docs/PARTNER_SETUP_PLAN.md §Slice 8).
--
-- Slot note: V025 = Lane A (lifecycle), V026..V028 = Lane B (credentials
-- core), V029 = Lane C (regulatory) — negotiated in the Slice 8 lane doc;
-- Lane D takes V030.
--
-- WHY
-- ---
-- The registry-side record of a partner's webhook subscription per credential
-- environment (SANDBOX | LIVE):
--
--   * During ONBOARDING the wizard's step-8 saves the DRAFT url + event-type
--     selection here (status=DRAFT, no endpoint yet).
--   * At activation (transition to SANDBOX, later LIVE) the
--     WebhookProvisioningService reads the draft, registers the endpoint with
--     the notification-webhook service (POST /v1/webhooks/endpoints), and
--     stamps endpoint_id + signing_secret_hash + last_rotated_at +
--     status=PROVISIONED on this row — inside the activation transaction.
--
-- NOT BITEMPORAL: unlike the step 1-7 child aggregates this is operational
-- wiring state, not a regulator-audited business fact — the ADR-007 audit log
-- captures every mutation (before/after canonical snapshots), which is the
-- history the examiners ask for. Rows are updated in place; at most ONE row
-- per (partner_id, environment) — a plain UNIQUE constraint, no
-- partial-unique emulation needed.
--
-- SECURITY (SEC-09 §4)
-- --------------------
-- signing_secret_hash stores ONLY the SHA-256 digest (lowercase hex) of the
-- signing secret the notification-webhook service minted; the plaintext is
-- surfaced exactly once in the activation response and never persisted on
-- either side. endpoint_id is the cross-service reference to
-- webhook_endpoint.id, carried as an id-STRING (no cross-database FK).
--
-- COMPATIBILITY
-- -------------
-- Plain TIMESTAMP, not TIMESTAMPTZ; TEXT CSV, not JSONB (H2 PG-mode compat,
-- same as V004..V024). Engine-managed identity (GenerationType.IDENTITY).
-- ADR-013 Expand discipline: wholly-new table; no in-place ALTERs.

CREATE TABLE partner_webhook_subscription (
    id                   BIGINT GENERATED ALWAYS AS IDENTITY,

    -- FK to the partners surrogate (V003/V004 BIGINT PK) — references the
    -- partner AGGREGATE via whichever row was current at write time;
    -- consumers resolve partners by partner_code (same note as V009..V024).
    partner_id           BIGINT       NOT NULL,

    -- Credential environment this subscription belongs to.
    environment          VARCHAR(10)  NOT NULL,

    -- HTTPS receiver URL (service-validated; notification-webhook is the
    -- storage-level backstop).
    url                  VARCHAR(512) NOT NULL,

    -- Comma-separated event types; NULL = "subscribe to all events" (the
    -- webhook_endpoint V003 convention).
    event_types          TEXT,

    -- notification-webhook's webhook_endpoint.id as an id-string; NULL while
    -- the subscription is still a DRAFT.
    endpoint_id          VARCHAR(40),

    -- SHA-256 hex digest of the issued signing secret; NULL while DRAFT.
    signing_secret_hash  VARCHAR(64),

    -- When the signing secret was last issued/rotated; NULL while DRAFT.
    last_rotated_at      TIMESTAMP,

    -- DRAFT (saved, not provisioned) / PROVISIONED / DISABLED.
    status               VARCHAR(12)  NOT NULL DEFAULT 'DRAFT',

    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_partner_webhook_subscription PRIMARY KEY (id),

    CONSTRAINT fk_partner_webhook_subscription_partner
        FOREIGN KEY (partner_id) REFERENCES partners (id),

    CONSTRAINT ck_partner_webhook_subscription_env CHECK (
        environment IN ('SANDBOX', 'LIVE')
    ),

    CONSTRAINT ck_partner_webhook_subscription_status CHECK (
        status IN ('DRAFT', 'PROVISIONED', 'DISABLED')
    ),

    -- One subscription per partner per environment; in-place updates keep it.
    CONSTRAINT uq_partner_webhook_subscription_env
        UNIQUE (partner_id, environment)
);

-- Detail-page lookup: "all subscriptions of partner P".
CREATE INDEX idx_partner_webhook_subscription_partner
    ON partner_webhook_subscription (partner_id);
