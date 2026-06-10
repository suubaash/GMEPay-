-- V002: effective-dating columns on partners (ticket 17.2-G01).
--
-- Window semantics are half-open [effective_from, effective_to):
--   * effective_from is INCLUSIVE  — a partner IS effective at exactly effective_from
--   * effective_to   is EXCLUSIVE  — a partner is NOT effective at exactly effective_to
--   * effective_to IS NULL means open-ended (effective until further notice)
--
-- Existing and seed rows default to the epoch so they are effective "since forever".
-- PostgreSQL-compatible; also valid under H2 in PostgreSQL mode.
ALTER TABLE partners ADD COLUMN effective_from TIMESTAMP NOT NULL DEFAULT TIMESTAMP '1970-01-01 00:00:00';
ALTER TABLE partners ADD COLUMN effective_to TIMESTAMP;
