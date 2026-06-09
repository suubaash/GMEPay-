-- V001: partners table owned by config-registry.
-- One row per partner (e.g. GMEREMIT, SENDMN). Carries the per-partner
-- settlement_rounding_mode that dictates how the partner's settlement liability
-- is booked at transaction commit (see MONEY_CONVENTION.md).
-- PostgreSQL-compatible; also valid under H2 in PostgreSQL mode.
CREATE TABLE partners (
    partner_id                 VARCHAR(32)  NOT NULL,
    type                       VARCHAR(16)  NOT NULL,
    settlement_currency        VARCHAR(3),
    settlement_rounding_mode   VARCHAR(16)  NOT NULL DEFAULT 'HALF_UP',
    created_at                 TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 TIMESTAMP,
    CONSTRAINT pk_partners PRIMARY KEY (partner_id)
);
