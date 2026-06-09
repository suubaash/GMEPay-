-- V001: partner_balance owns one row per partner holding the current prepaid balance.
-- PostgreSQL-compatible; also valid under H2 in PostgreSQL mode.
-- Balance mutations MUST use SELECT ... FOR UPDATE (see PartnerBalanceRepository.lockByPartnerId).
CREATE TABLE partner_balance (
    partner_id              VARCHAR(32)    NOT NULL,
    currency                VARCHAR(3)     NOT NULL,
    balance                 NUMERIC(20, 8) NOT NULL,
    low_balance_threshold   NUMERIC(20, 8),
    updated_at              TIMESTAMP      NOT NULL,
    CONSTRAINT pk_partner_balance PRIMARY KEY (partner_id)
);
