-- V032 — Configurable GROSS merchant fee rate, by (scheme × merchant type).
--
-- This is the INPUT to the fee waterfall that the V031 commission split consumes:
--   gross_merchant_fee = payout × merchant_fee_pct      (THIS table)
--   net                = gross − (payout × van_fee_pct) (scheme_commission_share)
--   gme                = net × gme_share_pct            (scheme_commission_share)
--   partner            = gme × partner_share_pct        (partner_commission_share)
--
-- ZeroPay sets the merchant fee by merchant category (0.80–2.20% typical), so the
-- rate is keyed by (scheme_id, merchant_type). merchant_type NULL = the scheme's
-- default rate (applies to any type with no specific row) — there is always a
-- fallback. The resolved rate is snapshotted onto the transaction at creation
-- (the rate that applied then), so later config changes never retro-alter a
-- booked settlement.
--
-- Same SCD-6 bitemporal discipline as scheme_commission_share / partner_fee_schedule
-- (V031/V018, ADR-010): rows are NEVER updated in place; a save supersedes the
-- current set and inserts a fresh one. CURRENT config = superseded_at IS NULL.

CREATE TABLE merchant_fee_schedule (
    id                BIGSERIAL     NOT NULL,
    scheme_id         VARCHAR(40)   NOT NULL,
    merchant_type     VARCHAR(40),
    merchant_fee_pct  NUMERIC(7, 4) NOT NULL,
    valid_from        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to          TIMESTAMP,
    recorded_at       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at     TIMESTAMP,
    CONSTRAINT pk_merchant_fee_schedule PRIMARY KEY (id),
    CONSTRAINT ck_merchant_fee_schedule_pct CHECK (
        merchant_fee_pct >= 0 AND merchant_fee_pct <= 1
    )
);

-- Serves "current rows for a scheme" (superseded_at IS NULL).
CREATE INDEX idx_merchant_fee_schedule_current
    ON merchant_fee_schedule (scheme_id, superseded_at);
