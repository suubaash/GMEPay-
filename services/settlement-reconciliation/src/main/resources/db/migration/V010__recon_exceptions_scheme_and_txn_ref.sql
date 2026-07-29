-- V010 (GAP T2-2): let the ops exception queue carry cross-border, per-TRANSACTION breaks.
--
-- The ZeroPay recon is merchant-level (one break per merchant per settlement file), so
-- recon_exceptions was keyed on merchant_id alone. The SENDMN three-way tie-out is
-- transaction-level: our txn record vs our prefunding USD movement vs the adapter's record of
-- what SendMN confirmed. Each break therefore needs the transaction it belongs to, and — now
-- that more than one scheme writes here — which scheme raised it.
--
-- Both columns are NULLABLE (Expand discipline): every existing ZeroPay row stays valid and the
-- exception API/ops workflow is unchanged; scheme is simply null on legacy ZeroPay rows.
--   scheme   — 'SENDMN' / 'ZEROPAY' / … (the recon lane that raised the break)
--   txn_ref  — transaction reference (SENDMN: the hub partner reference, i.e. the join key shared
--              by transaction-mgmt, the prefunding deduct and smn_payments.hub_reference)
-- PostgreSQL-compatible SQL that also runs under H2 (MODE=PostgreSQL).

ALTER TABLE recon_exceptions ADD COLUMN scheme  VARCHAR(32);
ALTER TABLE recon_exceptions ADD COLUMN txn_ref VARCHAR(64);

CREATE INDEX idx_recon_exceptions_scheme ON recon_exceptions (scheme);
CREATE INDEX idx_recon_exceptions_txn_ref ON recon_exceptions (txn_ref);
