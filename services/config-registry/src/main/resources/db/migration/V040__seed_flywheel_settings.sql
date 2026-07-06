-- V040: Seed the flywheel.* ops-entered growth-loop metrics (QR_HUB_GROWTH_FLYWHEEL.md §5,
-- tracker item #2 — docs/QR_HUB_FLYWHEEL_TRACKER.md).
--
-- WHY
-- ---
-- Three of the seven loop KPIs on the Admin UI Flywheel page have no
-- system-of-record feed yet (scheme merchant counts, adapter certification
-- milestones, payer-level ids), so operators enter them here via the Platform
-- Settings editor and ops-partner-bff's GET /v1/admin/flywheel reads them.
-- Seeding the rows makes the keys discoverable in the editor and pins the
-- NUMBER type so PUT validation applies from the first edit.
--
-- '0' means "not yet measured": the BFF treats non-positive values as unset and
-- the Flywheel page renders a dash, never a fake zero.
--
-- DDL DISCIPLINE
-- --------------
-- Additive only — seed INSERTs against the V039 table; portable across
-- PostgreSQL and H2 (PostgreSQL mode).

INSERT INTO platform_settings ("key", "value", value_type, description, updated_at, updated_by) VALUES
    ('flywheel.acceptance_points',         '0', 'NUMBER', 'Flywheel: acceptance points reachable (sum of merchant counts across live schemes). 0 = not yet measured.', CURRENT_TIMESTAMP, 'system'),
    ('flywheel.adapter_time_to_live_days', '0', 'NUMBER', 'Flywheel: days from scheme-adapter kickoff to certified live (loop A health). 0 = not yet measured.',        CURRENT_TIMESTAMP, 'system'),
    ('flywheel.monthly_active_payers',     '0', 'NUMBER', 'Flywheel: monthly active cross-border payers (loops D/E health). 0 = not yet measured.',                     CURRENT_TIMESTAMP, 'system');
