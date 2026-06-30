-- V008 — Wave-3 rate-lock pool columns (margin-accurate FX1015).
--
-- payment-executor now carries the rate-lock pool on the create / status-patch commit
-- contract (CreateTransactionRequest + StatusPatchRequest). Persisting these lets the
-- committed-FX projection (GET /v1/transactions/fx-committed) derive a MARGIN-ACCURATE
-- offerRateColl = send_amount / (collection_usd - collection_margin_usd)  [BOK FX1015 #14]
-- using the REAL collection-leg USD amount + collection margin, instead of the prior
-- zero-margin approximation over the prefund-deducted-USD proxy.
--
-- collection_margin_usd / payout_margin_usd already exist (V007). These add the rest of
-- the pool. All NULLABLE and populated best-effort; legacy rows / same-ccy legs leave them
-- null and the projection falls back to its proxies (zero-margin approximation preserved).
-- H2 (PostgreSQL mode) needs one ALTER per column.

ALTER TABLE transactions ADD COLUMN IF NOT EXISTS collection_usd   NUMERIC(20,8);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS cost_rate_coll   NUMERIC(20,8);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS cost_rate_pay    NUMERIC(20,8);
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS payout_usd_cost  NUMERIC(20,8);
