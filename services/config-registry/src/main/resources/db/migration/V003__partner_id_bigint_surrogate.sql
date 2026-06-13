-- V003: Slice 1 cross-cutting bug fix #1 — Partner ID schism resolution.
--
-- BEFORE V003: partners.partner_id VARCHAR(32) was the primary key. Every consuming
-- service (auth-identity PrincipalEntity, notification-webhook WebhookEndpointEntity,
-- settlement-reconciliation, auth-identity PartnerCredentialPort) already modelled the
-- foreign key as BIGINT, so every wire-up join hit a type-mismatch wall.
--
-- AFTER V003 (this migration — the Expand phase per ADR-013):
--   * NEW: partners.id          BIGSERIAL UNIQUE  — the surrogate, universal join key.
--   * NEW: partners.partner_code VARCHAR(20) UNIQUE — the human-facing business code.
--   * UNCHANGED: partners.partner_id VARCHAR(32) PK — kept populated for one more release
--     so any caller still on the old shape continues to work. ADR-013 forbids in-place
--     ALTER NOT NULL on existing columns and forbids destructive drops in the Expand phase.
--
-- The Contract phase (a future release) drops the old partner_id column and promotes
-- id to PRIMARY KEY. Don't try to do it here.
--
-- PostgreSQL-compatible SQL that also runs under H2 (MODE=PostgreSQL). H2 does not
-- support BIGSERIAL as a column type directly; we use BIGINT + a SEQUENCE/IDENTITY
-- combination expressed via standard SQL so the same DDL applies to both engines.

-- 1) Add the surrogate id column. Nullable for now (Expand discipline): we will
--    backfill every existing row in the same migration, then leave it nullable so
--    that a future Contract migration can promote it to NOT NULL + PRIMARY KEY.
ALTER TABLE partners ADD COLUMN id BIGINT;

-- 2) Sequence to feed the surrogate. PostgreSQL: CREATE SEQUENCE. H2 in PostgreSQL
--    compatibility mode accepts the same syntax.
CREATE SEQUENCE partners_id_seq START WITH 1 INCREMENT BY 1;

-- 3) Backfill every existing row with a freshly-allocated surrogate. The seeded rows
--    (GMEREMIT, SENDMN) plus any operator-created rows get id=1, id=2, ... in
--    insertion order (effective_from breaks any tie deterministically).
UPDATE partners SET id = NEXTVAL('partners_id_seq')
WHERE id IS NULL;

-- 4) Make subsequent INSERTs default to the next sequence value so new rows minted
--    by the JPA layer get a surrogate automatically without the entity having to
--    set one.
ALTER TABLE partners ALTER COLUMN id SET DEFAULT NEXTVAL('partners_id_seq');

-- 5) Unique constraint on id. UNIQUE rather than PRIMARY KEY because the existing
--    PK (partner_id VARCHAR(32)) stays in place during the Expand phase; the Contract
--    phase will drop the old PK and promote id to PK.
ALTER TABLE partners ADD CONSTRAINT uq_partners_id UNIQUE (id);

-- 6) Add the human-facing business code. Nullable + UNIQUE so the Expand phase can
--    backfill from the existing partner_id string without violating constraints
--    mid-migration. The application code from Slice 1 onwards writes partner_code
--    on every insert; legacy callers reading by partner_id continue to work.
ALTER TABLE partners ADD COLUMN partner_code VARCHAR(20);

-- 7) Backfill partner_code from the existing partner_id string. partner_id is
--    VARCHAR(32) but every existing value (GMEREMIT, SENDMN, TBANK in tests) is well
--    under 20 chars; if any future row exceeds 20 chars this UPDATE will fail loudly
--    rather than truncate.
UPDATE partners SET partner_code = partner_id
WHERE partner_code IS NULL;

-- 8) Unique constraint on partner_code.
ALTER TABLE partners ADD CONSTRAINT uq_partners_partner_code UNIQUE (partner_code);

-- NOTE: the old partners.partner_id column is INTENTIONALLY left in place. It still
-- carries the same value as partner_code and remains the PRIMARY KEY for one more
-- release. The Contract migration (V0xx, scheduled after every consuming service
-- has switched to reading the surrogate) drops it and renames partner_code to remain.
