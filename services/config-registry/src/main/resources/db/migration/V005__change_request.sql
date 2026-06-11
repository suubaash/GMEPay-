-- V005: change_request table per ADR-008.
--
-- Owns the 4-eyes maker-checker primitive for every regulated mutation. One row
-- per proposed change; walks DRAFT → PROPOSED → APPROVED → APPLIED (or → REJECTED)
-- via the Spring State Machine wired in lib-change-request. Only APPLIED rows
-- mutate the underlying aggregate, and they do so in the same transaction as the
-- audit_log write (ADR-007).
--
-- The 4-eyes invariant is enforced procedurally by the FSM (lib-change-request)
-- AND structurally by the CHECK constraint below — defence in depth. The system
-- carve-out (proposed_by='system' AND approved_by='system') exists for
-- auto-suspend-on-prefunding-breach and sanctions-hit auto-rejections that have
-- no human checker (per ADR-008 ¶Consequences).
--
-- PostgreSQL-compatible SQL that also runs under H2 (MODE=PostgreSQL). H2 has
-- weaker JSONB and array support, so we use JSONB / TEXT[] in the source-of-truth
-- shape and rely on H2's PostgreSQL-mode acceptance of both: JSONB collapses to
-- text storage, TEXT[] is mapped to ARRAY. The JPA boundary handles the typing.

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
    -- ADR-008 4-eyes invariant: the maker cannot also be the checker, except for
    -- the system-driven carve-out (auto-suspend, sanctions-hit auto-reject) where
    -- both columns may equal 'system'.
    CONSTRAINT ck_change_request_four_eyes CHECK (
        approved_by IS NULL
        OR proposed_by IS DISTINCT FROM approved_by
        OR (proposed_by = 'system' AND approved_by = 'system')
    ),
    -- A REJECTED row must carry a reason; APPLIED rows must carry an approval
    -- (the approval is what authorised the apply). DRAFT/PROPOSED carry neither
    -- yet; APPROVED carries the approval but is not yet applied.
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

-- Surrogate id sequence. Same dual-engine pattern as partners_id_seq (V003): the
-- application layer pulls NEXTVAL before INSERT so PostgreSQL and H2 in
-- PostgreSQL-mode behave identically without leaning on Hibernate-specific
-- generated-id support.
CREATE SEQUENCE change_request_id_seq START WITH 1 INCREMENT BY 1;
ALTER TABLE change_request ALTER COLUMN id SET DEFAULT NEXTVAL('change_request_id_seq');

-- Aggregate lookup index: a partner detail screen needs to list every
-- change_request touching that partner, in newest-first order. Composite
-- (aggregate_type, aggregate_id) so the index serves filtered lookups across
-- different aggregate kinds owned by this service.
CREATE INDEX idx_change_request_aggregate
    ON change_request (aggregate_type, aggregate_id);

-- State + proposed_at index: the Approval Queue UI (Slice 2) pages over rows in
-- state=PROPOSED ordered by proposed_at ASC (oldest first). A single composite
-- supports that filter and the sort.
CREATE INDEX idx_change_request_state_proposed_at
    ON change_request (state, proposed_at);
