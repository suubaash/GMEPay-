-- V005 — snapshot the GROSS merchant fee rate on the transaction.
--
-- The rate is resolved from config-registry's merchant_fee_schedule (V032) at
-- creation and frozen here — the rate that applied when the txn happened, immune
-- to later config changes (financially correct for settlement). NUMERIC(7,4):
-- e.g. 0.0080 = 0.80%. Consumed by settlement's NET calc and the commission
-- split. Nullable (legacy rows + transactions created before resolution wiring).
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS merchant_fee_rate NUMERIC(7,4);
