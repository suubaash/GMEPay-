-- V007: persist per-partner AML caps pushed from config-registry (IR-pf-2) alongside the
-- credit headroom (credit_limit, V005). Until now the daily/monthly/annual amount caps and the
-- daily transaction-count cap arrived per-request on /cumulative-charge; config-registry now pushes
-- them once via PUT /internal/v1/prefunding/{partnerId}/credit-limit and they are STORED here, so the
-- AML gate can read them without a per-request cap argument (per-request caps still override when supplied).
-- All caps NULLABLE: NULL = "no cap configured" (unlimited for that period / no velocity cap).
-- Money NUMERIC(19,4) major USD per MONEY_CONVENTION.md (matches cumulative_usage_ledger.amount_usd).
-- H2 (MODE=PostgreSQL) + PostgreSQL portable.
ALTER TABLE partner_balance ADD COLUMN aml_daily_cap_usd       NUMERIC(19, 4);
ALTER TABLE partner_balance ADD COLUMN aml_monthly_cap_usd     NUMERIC(19, 4);
ALTER TABLE partner_balance ADD COLUMN aml_annual_cap_usd      NUMERIC(19, 4);
ALTER TABLE partner_balance ADD COLUMN aml_daily_txn_count_cap INTEGER;
