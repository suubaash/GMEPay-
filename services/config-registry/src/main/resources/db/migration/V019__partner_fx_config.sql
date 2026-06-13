-- V019: partner_fx_config child table — Slice 6 "Commercial Terms"
-- (docs/PARTNER_SETUP_PLAN.md §Slice 6).
--
-- WHY BITEMPORAL (ADR-010)
-- ------------------------
-- The FX margin and reference-rate source decide the rate every cross-border
-- transaction converts at — a mis-stated margin is money lost on every single
-- payment, and "what margin was configured when this quote was issued?" is the
-- first dispute question. Same SCD-6 column pairs as V004/V009/V011/V013/V015/
-- V018:
--
--   * valid_from  / valid_to       — business time (when the config is true)
--   * recorded_at / superseded_at  — transaction time (when we wrote it; NULL
--                                     on rows that are still current)
--
-- Storage discipline: rows are NEVER UPDATEd in place. A wizard step-6 save is
-- a paired write — (UPDATE current row SET superseded_at = now()) + (INSERT
-- fresh row with recorded_at = now()), both halves sharing one MICROS-truncated
-- instant (see FxConfigService).
--
-- ONE CURRENT ROW PER PARTNER
-- ---------------------------
-- One FX config is current per partner at a time, but (as with V013/V015) we
-- do not partial-index-enforce it — the service serialises writes per partner
-- in one transaction, and the composite (partner_id, superseded_at) index
-- serves the hot `WHERE partner_id = ? AND superseded_at IS NULL` lookup on
-- both engines.
--
-- COMPATIBILITY
-- -------------
-- Plain TIMESTAMP, not TIMESTAMPTZ (H2 PG-mode compat, same as V004..V018).
-- BIGSERIAL surrogate id, engine-managed (GenerationType.IDENTITY).
--
-- ADR-013 Expand discipline: wholly-new table; no in-place ALTERs.

CREATE TABLE partner_fx_config (
    -- BIGSERIAL surrogate, engine-managed — same id strategy as V015/V018.
    id                     BIGSERIAL     NOT NULL,

    -- FK to the partners surrogate (V003/V004 BIGINT PK); consumers resolve
    -- partners by partner_code (same note as V009..V018).
    partner_id             BIGINT        NOT NULL,

    -- FX margin in basis points layered on top of the reference rate.
    -- NUMERIC(7,4): max 999.9999 bps (= 10%), 4-decimal precision.
    margin_bps             NUMERIC(7,4)  NOT NULL DEFAULT 0,

    -- Where the reference rate comes from (PARTNER_SETUP_PLAN §Slice 6):
    -- SEOUL_FX_BROKER (서울외국환중개 official fixing), PARTNER_PROVIDED (the
    -- partner streams their own rate) or MID_MARKET (composite mid).
    reference_rate_source  VARCHAR(30)   NOT NULL,

    -- How long an issued quote stays honoured, seconds. Default 5 minutes;
    -- floor 60s (no flash quotes), ceiling 30min (no stale-rate risk).
    quote_hold_seconds     INT           NOT NULL DEFAULT 300,

    -- Business time (ADR-010). Half-open [valid_from, valid_to); NULL valid_to
    -- = open-ended.
    valid_from             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to               TIMESTAMP,

    -- Transaction time (ADR-010). recorded_at defaulted as a safety net but
    -- set explicitly (MICROS-truncated) by the application so paired writes
    -- share one instant; superseded_at is NULL on current rows.
    recorded_at            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at          TIMESTAMP,

    CONSTRAINT pk_partner_fx_config PRIMARY KEY (id),

    CONSTRAINT fk_partner_fx_config_partner
        FOREIGN KEY (partner_id) REFERENCES partners (id),

    CONSTRAINT ck_partner_fx_config_source CHECK (
        reference_rate_source IN ('SEOUL_FX_BROKER', 'PARTNER_PROVIDED', 'MID_MARKET')
    ),

    CONSTRAINT ck_partner_fx_config_hold CHECK (
        quote_hold_seconds BETWEEN 60 AND 1800
    ),

    CONSTRAINT ck_partner_fx_config_margin CHECK (margin_bps >= 0)
);

-- Hot-path lookup: "current FX config for partner P" — same composite-index
-- choice as V009..V018.
CREATE INDEX idx_partner_fx_config_current
    ON partner_fx_config (partner_id, superseded_at);
