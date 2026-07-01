-- V035: partner_rule rate-source provenance — Wave-3 read-contract wiring.
--
-- WHY
-- ---
-- rate-fx prices a quote from two cost-rate legs (collection + payout). Each
-- leg's rate can come from one of several provenances:
--
--   * IDENTITY — same-currency short-circuit (rate is 1, no FX);
--   * LIVE     — the live market feed (the default for a cross-border leg);
--   * MANUAL   — an operator-pinned override rate;
--   * PARTNER  — a partner-supplied (Partner-B) quote.
--
-- config-registry owns the partner pricing rule (V017), so it is the natural
-- producer of this provenance. These two columns let rate-fx read, per rule,
-- which source to draw each leg's cost rate from — emitted on the canonical
-- RuleView (lib-api-contracts, Wave-3 additive) as rateCollSource/ratePaySource.
--
-- ADR-013 EXPAND
-- --------------
-- Additive nullable columns with a DEFAULT — the Expand step of an
-- expand/contract migration. Existing rows backfill to the column DEFAULT
-- 'LIVE' (the safe assumption: a pre-existing cross-border rule priced off the
-- live feed). The application normalises NULL → 'LIVE' on read too, so a row
-- inserted before this column existed and never re-saved still reads 'LIVE'.
--
-- COMPATIBILITY
-- -------------
-- Plain ALTER ... ADD COLUMN, engine-neutral (PG + H2 PG-mode). The CHECK pins
-- the four-value roster the wire String documents.

ALTER TABLE partner_rule
    ADD COLUMN rate_coll_source VARCHAR(10) NOT NULL DEFAULT 'LIVE';

ALTER TABLE partner_rule
    ADD COLUMN rate_pay_source  VARCHAR(10) NOT NULL DEFAULT 'LIVE';

ALTER TABLE partner_rule
    ADD CONSTRAINT ck_partner_rule_rate_coll_source CHECK (
        rate_coll_source IN ('IDENTITY', 'LIVE', 'MANUAL', 'PARTNER')
    );

ALTER TABLE partner_rule
    ADD CONSTRAINT ck_partner_rule_rate_pay_source CHECK (
        rate_pay_source IN ('IDENTITY', 'LIVE', 'MANUAL', 'PARTNER')
    );
