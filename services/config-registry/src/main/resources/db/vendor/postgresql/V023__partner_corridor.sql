-- V023 (POSTGRESQL VENDOR VARIANT): partner_corridor child table — Slice 7
-- "Schemes & Corridors" (docs/PARTNER_SETUP_PLAN.md §Slice 7).
--
-- ENGINE SPLIT (same mechanism as V004)
-- -------------------------------------
-- This file is the PostgreSQL twin of db/vendor/h2/V023__partner_corridor.sql.
-- The two differ in EXACTLY ONE token: the `STORED` keyword on the generated
-- `is_current` column. PostgreSQL 12+ REQUIRES `GENERATED ALWAYS AS (...)
-- STORED`; H2 (2.2 / 2.3, every compatibility mode) REJECTS the STORED
-- keyword — there is no single spelling both engines parse. Flyway resolves
-- the right variant through the vendor-substituted location
-- `classpath:db/vendor/{vendor}` configured in application.properties.
-- Keep the two files in lock-step when either changes.
--
-- WHY
-- ---
-- A corridor is the (src_country, src_ccy) → (dst_country, dst_ccy) lane a
-- partner is allowed to move money over. The wizard's step-7 corridor-matrix
-- builder saves them here; the gateway's corridor gate (Slice 7 exit gate:
-- "transactions on an inactive corridor are rejected") and the data-driven
-- SchemeRouter read them.
--
-- WHY BITEMPORAL (ADR-010)
-- ------------------------
-- "Why did this transaction route over corridor X on date D?" is a regulator
-- question — the corridor set that answered it must be reconstructable. Same
-- SCD-6 column pairs as V004..V021:
--
--   * valid_from  / valid_to       — business time (when the corridor fact is true)
--   * recorded_at / superseded_at  — transaction time (when we wrote it; NULL
--                                     on rows that are still current)
--
-- Storage discipline: rows are NEVER UPDATEd in place. A wizard step-7 save is
-- a bulk replace — every current corridor row of the partner is superseded
-- (superseded_at = now) and the new set inserted (recorded_at = now), both
-- halves sharing one MICROS-truncated instant (see PartnerCorridorService).
--
-- ONE CURRENT ROW PER CORRIDOR KEY
-- --------------------------------
-- At most one CURRENT row per (partner_id, src_country, src_ccy, dst_country,
-- dst_ccy). PostgreSQL would express this as `CREATE UNIQUE INDEX ... WHERE
-- superseded_at IS NULL`, but H2 (the @DataJpaTest engine) supports neither a
-- WHERE clause on CREATE INDEX nor expression-based unique indexes. We use the
-- V004 cross-engine equivalent: a stored GENERATED `is_current` column that is
-- TRUE on current rows and NULL on superseded rows, included as the last
-- column of a plain composite UNIQUE index. Both engines treat tuples
-- containing a NULL as distinct under UNIQUE, so superseded rows never
-- collide; current rows (is_current = TRUE) collide on the corridor key,
-- giving the desired guarantee with ZERO application bookkeeping (unlike
-- V017's app-maintained current_rule_key, the DB recomputes is_current on the
-- supersede UPDATE itself).
--
-- COMPATIBILITY
-- -------------
-- Plain TIMESTAMP, not TIMESTAMPTZ (H2 PG-mode compat, same as V004..V021).
-- BIGINT GENERATED ALWAYS AS IDENTITY surrogate id, engine-managed
-- (GenerationType.IDENTITY) — both engines parse the standard SQL spelling.
-- ADR-013 Expand discipline: wholly-new table; no in-place ALTERs.

CREATE TABLE partner_corridor (
    -- Identity surrogate, engine-managed: rows are minted fresh on every SCD-6
    -- write and nothing outside this service joins on their ids.
    id             BIGINT GENERATED ALWAYS AS IDENTITY,

    -- FK to the partners surrogate (V003/V004 BIGINT PK); consumers resolve
    -- partners by partner_code (same note as V009..V021).
    partner_id     BIGINT       NOT NULL,

    -- Corridor key: source side. ISO-3166 alpha-2 country + ISO-4217 currency.
    src_country    CHAR(2)      NOT NULL,
    src_ccy        CHAR(3)      NOT NULL,

    -- Corridor key: destination side.
    dst_country    CHAR(2)      NOT NULL,
    dst_ccy        CHAR(3)      NOT NULL,

    -- When the corridor opens for live traffic; NULL = not yet scheduled.
    go_live_date   DATE,

    -- Active toggle — the Slice 7 exit gate rejects transactions on inactive
    -- corridors at the gateway. Toggling rides a fresh SCD-6 row, not an
    -- in-place UPDATE.
    is_active      BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Business time (ADR-010). Half-open [valid_from, valid_to); NULL valid_to
    -- = open-ended.
    valid_from     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to       TIMESTAMP,

    -- Transaction time (ADR-010). recorded_at defaulted as a safety net but
    -- set explicitly (MICROS-truncated) by the application so paired writes
    -- share one instant; superseded_at is NULL on current rows.
    recorded_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at  TIMESTAMP,

    -- Current-row marker for the partial-unique emulation (see header note):
    -- TRUE while current, NULL once superseded. DB-computed — the supersede
    -- UPDATE vacates the index slot automatically.
    is_current     BOOLEAN GENERATED ALWAYS AS
                       (CASE WHEN superseded_at IS NULL THEN TRUE END) STORED,

    CONSTRAINT pk_partner_corridor PRIMARY KEY (id),

    CONSTRAINT fk_partner_corridor_partner
        FOREIGN KEY (partner_id) REFERENCES partners (id)
);

-- Hot-path lookup: "current corridor set for partner P" filters on
-- partner_id = ? AND superseded_at IS NULL — the composite serves it on both
-- engines (same choice as V009..V021).
CREATE INDEX idx_partner_corridor_current
    ON partner_corridor (partner_id, superseded_at);

-- Partial-unique enforcement: at most one CURRENT row per
-- (partner_id, src_country, src_ccy, dst_country, dst_ccy) — see header note.
CREATE UNIQUE INDEX partner_corridor_current
    ON partner_corridor (partner_id, src_country, src_ccy,
                         dst_country, dst_ccy, is_current);
