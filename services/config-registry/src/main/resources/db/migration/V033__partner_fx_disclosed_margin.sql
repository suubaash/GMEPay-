-- V033__partner_fx_disclosed_margin.sql
-- Step 10 (non-blocking): capture an OPTIONAL "disclosed partner margin" transparency flag on the
-- partner FX config. Whether the partner's FX margin is disclosed (to the customer / shared with
-- GME) is a reporting/compliance attribute — it does NOT affect pricing. Expand phase (ADR-013):
-- add the column NOT NULL with a safe DEFAULT so existing rows backfill to "not disclosed".
-- Compatible with PostgreSQL and H2 PostgreSQL mode.

ALTER TABLE partner_fx_config
    ADD COLUMN disclosed_partner_margin BOOLEAN NOT NULL DEFAULT FALSE;
