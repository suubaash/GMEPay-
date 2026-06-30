-- kyb-adapter service: adapter-side run log of every KYB verification.
--
-- One row per completed verification run (sanctions/PEP screening + business-
-- registration verification + document checks, collapsed into a single
-- PASS/FAIL/MANUAL_REVIEW decision). Keyed by the deterministic provider
-- reference so re-screening an unchanged subject is idempotent: the
-- orchestration looks the run up by provider_ref and returns it instead of
-- re-charging the vendor (unless an explicit force re-run is requested).
--
-- This is DISTINCT from config-registry's partner_kyb (the partner-side,
-- regulator-defensible record). kyb-adapter owns this run log so it can serve
-- GET /v1/kyb/result/{ref} without calling back into config-registry, and so a
-- screening verdict survives a restart.
--
-- PostgreSQL-compatible SQL that also works under H2 PostgreSQL mode
-- (no JSONB, no Postgres-only types).
CREATE TABLE kyb_screening (
    id                  BIGSERIAL    PRIMARY KEY,
    -- Deterministic vendor reference for the run (StubKybAdapter: "stub-<hash>").
    -- Unique: the idempotency key for re-screen.
    provider_ref        VARCHAR(96)  NOT NULL,
    partner_code        VARCHAR(64)  NOT NULL,
    -- Sanctions/PEP screening disposition (lib-kyb ScreeningResult.Status):
    -- CLEAR | HIT | NEEDS_REVIEW.
    screening_status    VARCHAR(16)  NOT NULL,
    -- Business-registration verdict: VERIFIED | NOT_FOUND | MISMATCH | SKIPPED.
    biz_reg_status      VARCHAR(16)  NOT NULL,
    biz_reg_ref         VARCHAR(96),
    -- Whether the required onboarding documents were all present in the request.
    documents_complete  BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Collapsed orchestration decision: PASS | FAIL | MANUAL_REVIEW.
    decision            VARCHAR(16)  NOT NULL,
    -- Human-readable one-line reason the decision came out as it did.
    decision_reason     VARCHAR(512),
    -- Count of sanctions/PEP hits at decision time (the hit detail rides the
    -- Kafka event + the synchronous response; only the count is persisted here).
    hit_count           INT          NOT NULL DEFAULT 0,
    screened_at         TIMESTAMP    NOT NULL,
    created_at          TIMESTAMP    NOT NULL
);

CREATE UNIQUE INDEX uq_kyb_screening_provider_ref ON kyb_screening(provider_ref);
CREATE INDEX idx_kyb_screening_partner_code ON kyb_screening(partner_code);
CREATE INDEX idx_kyb_screening_decision     ON kyb_screening(decision);
