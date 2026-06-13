-- V003: balance_alert — one row per raised low-balance tier alert (Slice 5 — Prefunding).
-- Raised by TierAlertEvaluator when the balance crosses DOWN through 95/85/70% of the
-- partner's low_balance_threshold, or BREACH when the balance goes negative.
-- Hysteresis: the evaluator consults the LATEST row per (partner_code, tier) — an
-- unacknowledged alert suppresses re-raising while the balance oscillates around the boundary.
-- PostgreSQL-compatible; also valid under H2 in PostgreSQL mode.
CREATE TABLE balance_alert (
    id            BIGSERIAL      PRIMARY KEY,
    partner_code  VARCHAR(20)    NOT NULL,
    tier          VARCHAR(10)    NOT NULL,
    balance_usd   NUMERIC(19, 4) NOT NULL,
    threshold_usd NUMERIC(19, 4) NOT NULL,
    raised_at     TIMESTAMP      NOT NULL,
    acknowledged  BOOLEAN        NOT NULL DEFAULT FALSE,
    CONSTRAINT ck_balance_alert_tier
        CHECK (tier IN ('TIER_70', 'TIER_85', 'TIER_95', 'BREACH'))
);

CREATE INDEX idx_balance_alert_partner_tier
    ON balance_alert (partner_code, tier, id);

CREATE INDEX idx_balance_alert_partner_raised
    ON balance_alert (partner_code, raised_at);
