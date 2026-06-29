-- V031 — Configurable commission sharing (two-sided, no fixed 70/30).
--
-- The merchant fee earned on a QR transaction is shared TWICE, and BOTH splits
-- are configurable (there is no hardcoded share anywhere):
--
--   1. SCHEME side (scheme_commission_share) — how the NET merchant fee is split
--      between GME and the QR scheme operator (e.g. ZeroPay). Configured in
--      "QR scheme setup". gme_share_pct is GME's fraction; the scheme keeps the
--      remainder. van_fee_pct is the VAN intermediary rate deducted from the
--      gross merchant fee BEFORE the split (gross - van = net, then split).
--
--   2. PARTNER side (partner_commission_share) — how GME's resulting commission
--      is shared with the wallet partner (GME Remit / SendMN / T-Bank).
--      Configured in "wallet partner setup". partner_share_pct is the partner's
--      fraction of GME's cut; GME keeps the remainder.
--
-- Both tables follow the same SCD-6 bitemporal discipline as
-- partner_fee_schedule (V018, ADR-010): rows are NEVER updated in place; a save
-- supersedes the current set (superseded_at = now) and inserts a fresh set
-- (recorded_at = now). The CURRENT config is "superseded_at IS NULL".
--
-- Shares are stored as NUMERIC(6,4) fractions in [0,1] (e.g. 0.7000 = 70%) to
-- match revenue_records.fee_share_pct and SchemeFeeSplitCalculator's
-- gmeFeeSharePct contract — no impedance when the split engine consumes them.

-- ---------------------------------------------------------------------------
-- Scheme-side commission share (GME ↔ scheme split of the net merchant fee).
-- Keyed by (scheme_id, direction); direction NULL = applies to all directions.
-- ---------------------------------------------------------------------------
CREATE TABLE scheme_commission_share (
    id             BIGSERIAL     NOT NULL,
    scheme_id      VARCHAR(40)   NOT NULL,
    direction      VARCHAR(10),
    gme_share_pct  NUMERIC(6, 4) NOT NULL,
    van_fee_pct    NUMERIC(7, 4) NOT NULL DEFAULT 0,
    valid_from     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to       TIMESTAMP,
    recorded_at    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at  TIMESTAMP,
    CONSTRAINT pk_scheme_commission_share PRIMARY KEY (id),
    CONSTRAINT ck_scheme_commission_share_direction CHECK (
        direction IN ('INBOUND', 'OUTBOUND', 'BOTH')
    ),
    CONSTRAINT ck_scheme_commission_share_gme_pct CHECK (
        gme_share_pct > 0 AND gme_share_pct <= 1
    ),
    CONSTRAINT ck_scheme_commission_share_van CHECK (van_fee_pct >= 0)
);

-- Serves "current rows for a scheme" (superseded_at IS NULL).
CREATE INDEX idx_scheme_commission_share_current
    ON scheme_commission_share (scheme_id, superseded_at);

-- ---------------------------------------------------------------------------
-- Partner-side commission share (GME ↔ partner split of GME's commission).
-- Keyed by (partner_id, scheme_id, direction); scheme_id/direction NULL = all.
-- ---------------------------------------------------------------------------
CREATE TABLE partner_commission_share (
    id                 BIGSERIAL     NOT NULL,
    partner_id         BIGINT        NOT NULL,
    scheme_id          VARCHAR(40),
    direction          VARCHAR(10),
    partner_share_pct  NUMERIC(6, 4) NOT NULL,
    valid_from         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to           TIMESTAMP,
    recorded_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at      TIMESTAMP,
    CONSTRAINT pk_partner_commission_share PRIMARY KEY (id),
    CONSTRAINT fk_partner_commission_share_partner
        FOREIGN KEY (partner_id) REFERENCES partners (id),
    CONSTRAINT ck_partner_commission_share_direction CHECK (
        direction IN ('INBOUND', 'OUTBOUND', 'BOTH')
    ),
    CONSTRAINT ck_partner_commission_share_pct CHECK (
        partner_share_pct >= 0 AND partner_share_pct <= 1
    )
);

CREATE INDEX idx_partner_commission_share_current
    ON partner_commission_share (partner_id, superseded_at);
