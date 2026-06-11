-- Test-only copy of the production V005 change_request DDL, renumbered V002 here
-- because this test migration directory ships a trimmed schema chain (only
-- partners + change_request) — see V001__partners_minimal.sql for the rationale.
--
-- KEEP THIS IN LOCKSTEP with src/main/resources/db/migration/V005__change_request.sql.
-- If the production V005 grows new columns / constraints, mirror them here so the
-- ChangeRequestServiceTest exercises the same shape.

CREATE TABLE change_request (
    id                    BIGINT          NOT NULL,
    aggregate_type        VARCHAR(64)     NOT NULL,
    aggregate_id          VARCHAR(64)     NOT NULL,
    state                 VARCHAR(16)     NOT NULL,
    proposed_by           VARCHAR(64)     NOT NULL,
    proposed_at           TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    approved_by           VARCHAR(64),
    approved_at           TIMESTAMP,
    rejected_reason       TEXT,
    payload_jsonb         TEXT,
    applies_to_field_set  TEXT,
    CONSTRAINT pk_change_request PRIMARY KEY (id),
    CONSTRAINT ck_change_request_four_eyes CHECK (
        approved_by IS NULL
        OR proposed_by IS DISTINCT FROM approved_by
        OR (proposed_by = 'system' AND approved_by = 'system')
    ),
    CONSTRAINT ck_change_request_state CHECK (
        state IN ('DRAFT', 'PROPOSED', 'APPROVED', 'APPLIED', 'REJECTED')
    ),
    CONSTRAINT ck_change_request_reject_reason CHECK (
        state <> 'REJECTED' OR rejected_reason IS NOT NULL
    ),
    CONSTRAINT ck_change_request_approved_meta CHECK (
        state NOT IN ('APPROVED', 'APPLIED')
        OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)
    )
);

CREATE SEQUENCE change_request_id_seq START WITH 1 INCREMENT BY 1;
ALTER TABLE change_request ALTER COLUMN id SET DEFAULT NEXTVAL('change_request_id_seq');

CREATE INDEX idx_change_request_aggregate
    ON change_request (aggregate_type, aggregate_id);

CREATE INDEX idx_change_request_state_proposed_at
    ON change_request (state, proposed_at);
