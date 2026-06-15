-- V005: Add ops-lifecycle fields to recon_exceptions for the exception API (BS-04 / UC-04-02).
--
-- New columns:
--   exception_status  VARCHAR(16)  — OPEN / RESOLVED / RE_RUN (ExceptionStatus enum)
--   operator_id       VARCHAR(128) — ops user who last acted on this exception (nullable)
--   resolution_note   TEXT         — free-text resolution note supplied by ops (nullable)
--   resolution_action VARCHAR(32)  — structured action taken: MANUAL_OVERRIDE, RESUBMIT,
--                                    WAIVED, etc. (nullable)
--   resolved_at       TIMESTAMP    — instant the exception was resolved (nullable)
--
-- Default OPEN for all existing rows (idempotent re-run safe).
-- PostgreSQL-compatible SQL that also runs under H2 (MODE=PostgreSQL).

ALTER TABLE recon_exceptions ADD COLUMN exception_status VARCHAR(16) NOT NULL DEFAULT 'OPEN';
ALTER TABLE recon_exceptions ADD COLUMN operator_id      VARCHAR(128);
ALTER TABLE recon_exceptions ADD COLUMN resolution_note  TEXT;
ALTER TABLE recon_exceptions ADD COLUMN resolution_action VARCHAR(32);
ALTER TABLE recon_exceptions ADD COLUMN resolved_at      TIMESTAMP;

CREATE INDEX idx_recon_exceptions_status
    ON recon_exceptions (exception_status);
