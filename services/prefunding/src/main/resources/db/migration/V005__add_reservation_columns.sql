-- V005: reservation-ledger support for the two-phase authorize/confirm flow (SETTLEMENT_FLOW_SPEC §7.1).
-- `reserved`     = sum of active holds for this partner (RESERVE - CAPTURE - RELEASE in ledger_entry).
-- `credit_limit` = per-partner credit headroom; wired from config-registry in a later slice (default 0).
-- Available funds for a new reservation = balance + credit_limit - reserved.
-- PostgreSQL-compatible; also valid under H2 in PostgreSQL mode.
ALTER TABLE partner_balance ADD COLUMN reserved NUMERIC(20, 8) NOT NULL DEFAULT 0;
ALTER TABLE partner_balance ADD COLUMN credit_limit NUMERIC(20, 8) NOT NULL DEFAULT 0;
