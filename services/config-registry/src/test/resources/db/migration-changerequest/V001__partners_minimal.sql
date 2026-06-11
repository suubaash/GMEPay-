-- Test-only minimal partners schema for the ChangeRequestServiceTest slice.
--
-- The full production V001..V006 chain pulls in V004's partial-unique-index DDL
-- that H2 (even in PostgreSQL mode) does not yet accept (CREATE UNIQUE INDEX ...
-- WHERE ... is a PostgreSQL extension). Slice 1B.2's own tests don't depend on
-- the bitemporal behaviour V004 introduces — they only need a partners table
-- the PartnerChangeRequestApplier can mutate, so we ship this trimmed seed
-- schema and ignore the production migration chain for this slice.
--
-- The Postgres-backed integration test (PartnerPostgresMigrationIT, docker-tagged)
-- still walks the full V001..V006 chain to keep the production schema honest.

CREATE TABLE partners (
    partner_id                 VARCHAR(32)  NOT NULL,
    id                         BIGINT,
    partner_code               VARCHAR(20),
    type                       VARCHAR(16)  NOT NULL,
    settlement_currency        VARCHAR(3),
    settlement_rounding_mode   VARCHAR(16)  NOT NULL DEFAULT 'HALF_UP',
    effective_from             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    effective_to               TIMESTAMP,
    created_at                 TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                 TIMESTAMP,
    CONSTRAINT pk_partners PRIMARY KEY (partner_id),
    CONSTRAINT uq_partners_id UNIQUE (id),
    CONSTRAINT uq_partners_partner_code UNIQUE (partner_code)
);

CREATE SEQUENCE partners_id_seq START WITH 1 INCREMENT BY 1;
ALTER TABLE partners ALTER COLUMN id SET DEFAULT NEXTVAL('partners_id_seq');
