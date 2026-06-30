-- Phase-2: once-per-batch guard for posting the Addendum-001 rounding residual to revenue-ledger.
-- residual_posted_at is set when the batch's rounding_residual has been POSTed to
-- revenue-ledger's /v1/journals/rounding-residual; a recon re-run checks this and never re-posts.
-- Nullable on existing rows (Expand discipline). H2 (MODE=PostgreSQL) + PostgreSQL portable.
ALTER TABLE settlement_batches ADD COLUMN residual_posted_at TIMESTAMP;
