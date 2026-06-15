-- transaction-mgmt OI-01: approval-timeout expiry sweeper.
-- Adds a failure_reason column so the sweeper (and any future FAILED paths)
-- can record WHY a transaction entered the FAILED terminal state.

ALTER TABLE transactions ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(64);
