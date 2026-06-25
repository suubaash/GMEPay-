-- Snapshot the fields the ZeroPay DETAIL files (ZP0065 payment detail / ZP0066 refund detail) need, onto
-- each settlement_line at request-window (ZP0061/ZP0063) write time. This makes the settlement_line the
-- AUTHORITATIVE record of what was settled: the detail run builds its rows from these columns alone, so it
-- ties out to the summary by construction and is independent of any later transaction status/date change
-- (a settled payment that is later REVERSED, or a claw-back of a prior-day payment, still appears correctly).
-- All nullable on existing rows (Expand discipline). H2 (MODE=PostgreSQL) + PostgreSQL portable.
ALTER TABLE settlement_lines ADD COLUMN merchant_id VARCHAR(64);
ALTER TABLE settlement_lines ADD COLUMN scheme_ref VARCHAR(64);
ALTER TABLE settlement_lines ADD COLUMN approved_at TIMESTAMP;            -- scheme approval instant (txn_time/date source)
ALTER TABLE settlement_lines ADD COLUMN merchant_fee_rate DECIMAL(9,6);  -- V005 snapshot rate, 0 for GROSS
