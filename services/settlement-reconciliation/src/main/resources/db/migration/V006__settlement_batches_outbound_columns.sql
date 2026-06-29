-- Outbound settlement-file lifecycle columns (WBS 7.1-T02/T08). All nullable on existing rows
-- (Expand discipline). H2 (MODE=PostgreSQL) + PostgreSQL portable. NOTE: the column is
-- settlement_window, NOT "window" — WINDOW is a reserved word in PostgreSQL.
ALTER TABLE settlement_batches ADD COLUMN file_type VARCHAR(8);
ALTER TABLE settlement_batches ADD COLUMN direction VARCHAR(16);
ALTER TABLE settlement_batches ADD COLUMN settlement_window VARCHAR(16);
ALTER TABLE settlement_batches ADD COLUMN settlement_type VARCHAR(1);          -- 'N' | 'G' | null (mixed)
ALTER TABLE settlement_batches ADD COLUMN net_settlement_amount DECIMAL(20,4);
ALTER TABLE settlement_batches ADD COLUMN merchant_fee_total DECIMAL(20,4) DEFAULT 0;
ALTER TABLE settlement_batches ADD COLUMN rounding_residual DECIMAL(20,8) DEFAULT 0;  -- Addendum-001, full precision
ALTER TABLE settlement_batches ADD COLUMN settlement_rounding_mode VARCHAR(16);
ALTER TABLE settlement_batches ADD COLUMN settle_currency VARCHAR(3);
ALTER TABLE settlement_batches ADD COLUMN file_checksum VARCHAR(64);           -- SHA-256 hex
ALTER TABLE settlement_batches ADD COLUMN record_count INT;
ALTER TABLE settlement_batches ADD COLUMN transmitted_at TIMESTAMP;            -- set by the SFTP task (later)
ALTER TABLE settlement_batches ADD COLUMN error_detail VARCHAR(1024);

-- Idempotency: at most one batch per (file_type, business_date, window).
CREATE UNIQUE INDEX uq_settlement_batches_idem
    ON settlement_batches (file_type, business_date, settlement_window);

-- Per-line booked/residual snapshot (Addendum-001). Booking is per-merchant today; these are
-- reserved for future per-line booking and carry the type/mode used.
ALTER TABLE settlement_lines ADD COLUMN booked_settlement_amount DECIMAL(20,4);
ALTER TABLE settlement_lines ADD COLUMN rounding_residual DECIMAL(20,8);
ALTER TABLE settlement_lines ADD COLUMN settlement_rounding_mode VARCHAR(16);
ALTER TABLE settlement_lines ADD COLUMN settlement_type VARCHAR(1);
