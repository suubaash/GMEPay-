-- V004: bitemporal effective dating on the partners aggregate (ADR-010).
--
-- WHY
-- ---
-- A 20-year-life payment platform must answer two distinct questions about any
-- historical fact:
--   (a) "what was true in business terms on date D" (business / valid time), and
--   (b) "what did we record about date D as of recording-time T" (transaction time).
--
-- Examples this V004 unlocks:
--   * A back-dated regulatory correction lands today (T) saying partner X's
--     settlement rounding mode was wrong on date D three weeks ago — we must keep
--     BOTH views (the wrong one we believed then + the corrected one we believe
--     now) so auditors can reconstruct what the system thought at any prior
--     moment.
--   * A historical settlement re-prices correctly against the partner row that
--     was current on the original business date, even after subsequent edits.
--
-- V002 added unitemporal effective dating (effective_from / effective_to). That
-- covers (a) but not (b) — there is no transaction-time axis. V004 closes the
-- gap by promoting the aggregate to SCD Type 6 with a current-flag (ADR-010):
--
--   * valid_from   / valid_to       — business time (when the fact IS/WAS true)
--   * recorded_at  / superseded_at  — transaction time (when we wrote it; NULL
--                                      on the row that is still current)
--
-- Storage discipline (ADR-010 + ADR-013): rows are NEVER `UPDATE`d in place.
-- Every change is a paired (UPDATE prior_row SET superseded_at=now()) +
-- (INSERT new_row) inside one transaction. The current view is
-- `WHERE superseded_at IS NULL`; the as-of view is
-- `WHERE valid_from <= D AND (valid_to IS NULL OR valid_to > D)
--    AND recorded_at <= T AND (superseded_at IS NULL OR superseded_at > T)`.
--
-- SCHEMA IMPACT
-- -------------
-- 1. Rename effective_from -> valid_from, effective_to -> valid_to (terminology
--    alignment with ADR-010; the semantics are identical).
-- 2. Add recorded_at TIMESTAMP NOT NULL DEFAULT now() — never NULL, every row
--    knows when it was recorded.
-- 3. Add superseded_at TIMESTAMP NULL — NULL on the current row, set to the
--    transaction-time instant on every prior historical row.
-- 4. Drop the legacy primary key on partner_id (VARCHAR). Under SCD-6 the same
--    partner_code will appear on multiple rows (one current + N historicals);
--    the row-level unique identity moves to the BIGINT surrogate `id` added by
--    V003. The Contract phase had been deferred per ADR-013, but bitemporal
--    storage forces our hand here — there is no path to multi-row-per-code
--    without dropping the legacy PK.
-- 5. Replace the plain UNIQUE constraint on partner_code with a partial unique
--    index that only enforces uniqueness across CURRENT rows (the ones where
--    superseded_at IS NULL). Historical rows for the same code are allowed,
--    in fact required, by SCD-6.
-- 6. Drop the redundant UNIQUE on id (V003 added it as a stepping stone) and
--    promote id to PRIMARY KEY. partner_id (VARCHAR) stays as a column so the
--    Expand-phase code that still reads it continues to work; only the PK
--    constraint is removed.
--
-- COMPATIBILITY
-- -------------
-- PostgreSQL-compatible DDL that also runs under H2 in PostgreSQL mode (the
-- engine the @DataJpaTest slices use). Two H2 limitations shape the SQL below:
--   * H2 does not support a WHERE clause on CREATE INDEX (partial indexes), and
--     does not accept expressions inside a UNIQUE INDEX column list. Step (7)
--     therefore uses a stored GENERATED column + a plain UNIQUE index to
--     emulate the PostgreSQL `WHERE superseded_at IS NULL` semantics.
--   * H2 does not recognise the `TIMESTAMPTZ` keyword as an alias for
--     `TIMESTAMP WITH TIME ZONE`. We use the portable spelling `TIMESTAMP` on
--     `recorded_at` / `superseded_at`; the JPA boundary stores values as
--     {@code java.time.Instant}, which Hibernate marshals as UTC under both
--     engines, so no information is lost.
--
-- ADR-013 compliance: no in-place ALTER NOT NULL on existing data columns. The
-- only NOT NULL added is on `recorded_at`, which has a default of now() so
-- existing rows backfill atomically without a separate UPDATE step.

-- 1) Business-time rename (effective_* -> valid_*). The H2 + PG-portable form
--    is `ALTER TABLE ... RENAME COLUMN`. Constraints/defaults follow the column.
ALTER TABLE partners RENAME COLUMN effective_from TO valid_from;
ALTER TABLE partners RENAME COLUMN effective_to TO valid_to;

-- 2) Transaction-time column: when this row was recorded. Defaulted to now() so
--    every existing row (the GMEREMIT/SENDMN seeds, plus any operator-created
--    rows from V001..V003) gets a non-NULL backfill in the same DDL step.
ALTER TABLE partners ADD COLUMN recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- 3) Transaction-time column: when this row stopped being current. NULL on the
--    current row by definition. Indexed via the partial unique index below.
ALTER TABLE partners ADD COLUMN superseded_at TIMESTAMP;

-- 4) Drop the legacy primary key on partner_id (VARCHAR). Under SCD-6 the same
--    business code appears on N+1 rows (1 current + N historicals); a PK on
--    partner_id would forbid that. We promote `id` (BIGINT, added by V003) to
--    the PK below. The partner_id column itself stays — Expand-phase code still
--    reads it; only the constraint is removed.
ALTER TABLE partners DROP CONSTRAINT pk_partners;

-- 5) Drop V003's UNIQUE on partner_code. Same reason as the PK drop: historical
--    rows for the same code must coexist. We replace this with a partial unique
--    index further down that only constrains the CURRENT row (one current row
--    per code, any number of historicals).
ALTER TABLE partners DROP CONSTRAINT uq_partners_partner_code;

-- 6) Drop V003's UNIQUE on id; promote id to PRIMARY KEY. Belt-and-braces:
--    PK already implies UNIQUE, so the old UNIQUE constraint is redundant once
--    id is the PK. The id column was made NOT NULL in spirit by V003's backfill
--    + DEFAULT NEXTVAL; we tighten it to NOT NULL here as part of the PK
--    promotion, which is safe because every row already has a populated id.
ALTER TABLE partners DROP CONSTRAINT uq_partners_id;
ALTER TABLE partners ALTER COLUMN id SET NOT NULL;
ALTER TABLE partners ADD CONSTRAINT pk_partners PRIMARY KEY (id);

-- 7) Partial-unique enforcement: at most one CURRENT row per partner_code, while
--    allowing any number of historical rows for the same code. PostgreSQL would
--    express this as `CREATE UNIQUE INDEX ... WHERE superseded_at IS NULL` (a
--    partial index), but H2 (used by the @DataJpaTest slices) supports neither
--    a WHERE clause on CREATE INDEX nor an expression-based unique index
--    (it expects a bare column identifier).
--
--    We use the cross-engine equivalent: a stored GENERATED column whose value
--    is the partner_code on current rows and NULL on historical rows, with a
--    plain UNIQUE index on the generated column. Both H2 (PostgreSQL mode) and
--    PostgreSQL treat multiple NULLs in a UNIQUE index as distinct (NULL != NULL
--    under UNIQUE), so historical rows do not collide with each other or with
--    the current row; current rows still collide on the partner_code value,
--    giving the desired "one current row per code" guarantee.
-- PostgreSQL requires the STORED keyword on GENERATED ALWAYS AS columns;
-- H2 (≥2.2 in PostgreSQL mode) accepts the same form. Without STORED, PG
-- raises `syntax error at end of input` and Flyway aborts the migration —
-- caught when the slice was first booted against a real PG instance.
ALTER TABLE partners ADD COLUMN current_partner_code VARCHAR(20)
    GENERATED ALWAYS AS (CASE WHEN superseded_at IS NULL THEN partner_code END) STORED;
CREATE UNIQUE INDEX partners_current ON partners(current_partner_code);
