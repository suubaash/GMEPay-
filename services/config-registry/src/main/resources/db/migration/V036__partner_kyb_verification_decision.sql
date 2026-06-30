-- V036: partner_kyb verification decision — Wave-3 onboarding→KYB-verify wiring.
--
-- WHY
-- ---
-- Slice 3 stored only the raw SANCTIONS screening verdict (screening_status +
-- screening_provider_ref + screened_at) from kyb-adapter's POST /v1/kyb/screen.
-- Wave-3 adds the FULL verification run (POST /v1/kyb/verify): kyb-adapter
-- collapses screening + business-registration + document-completeness into one
-- decision (APPROVED / MANUAL_REVIEW / REJECTED). These two columns give that
-- collapsed decision a home on the KYB row so the wizard's KYB step shows the
-- verdict and an analyst can disposition a MANUAL_REVIEW.
--
-- ADR-013 EXPAND
-- --------------
-- Additive nullable columns — NULL on rows that predate a verify run (only a
-- screen, or nothing). The verify run reuses the existing
-- screening_provider_ref / screening_status / screened_at columns for its
-- screening evidence and writes the collapsed verdict here.
--
-- COMPATIBILITY
-- -------------
-- Plain ALTER ... ADD COLUMN, engine-neutral (PG + H2 PG-mode). No CHECK: the
-- decision roster is owned by kyb-adapter and may grow; this service stores it
-- as an opaque String (the same loose-coupling choice as the rate-source wire).

ALTER TABLE partner_kyb
    ADD COLUMN verification_decision VARCHAR(20);

ALTER TABLE partner_kyb
    ADD COLUMN verification_decision_reason VARCHAR(500);
