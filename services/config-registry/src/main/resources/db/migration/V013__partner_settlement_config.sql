-- V013: partner_settlement_config child table — Slice 4 "Banking & Settlement"
-- (docs/PARTNER_SETUP_PLAN.md §Slice 4).
--
-- WHY BITEMPORAL (ADR-010)
-- ------------------------
-- The settlement cycle / cutoff / rail decide WHEN partner money moves — a
-- mis-stated cycle is a treasury incident and the regulator asks "what did the
-- config say when batch X was cut?". Same SCD-6 column pairs as partners
-- (V004), partner_contact (V009), partner_kyb (V011):
--
--   * valid_from  / valid_to       — business time (when the config is true)
--   * recorded_at / superseded_at  — transaction time (when we wrote it; NULL
--                                     on rows that are still current)
--
-- Storage discipline: rows are NEVER UPDATEd in place. A wizard step-4 save is
-- a paired write — (UPDATE current row SET superseded_at = now()) + (INSERT
-- fresh row with recorded_at = now()), both halves sharing one MICROS-truncated
-- instant (see SettlementConfigService).
--
-- ONE CURRENT ROW PER PARTNER
-- ---------------------------
-- One settlement config is current per partner at a time, but (as with V009 /
-- V011) we do not partial-index-enforce it — the service serialises writes per
-- partner in one transaction, and the composite (partner_id, superseded_at)
-- index serves the hot `WHERE partner_id = ? AND superseded_at IS NULL` lookup
-- on both engines.
--
-- COMPATIBILITY
-- -------------
-- Plain TIMESTAMP, not TIMESTAMPTZ (H2 PG-mode compat, same as V004..V012).
-- TIME for the cutoff: a wall-clock time-of-day evaluated in cutoff_timezone —
-- storing it as TIME + IANA zone id (instead of a zoned type) is portable
-- across PostgreSQL and H2 and matches how treasury states cutoffs
-- ("16:30 Asia/Seoul"). BIGSERIAL surrogate id, engine-managed
-- (GenerationType.IDENTITY on the entity).
--
-- ADR-013 Expand discipline: wholly-new table; no in-place ALTERs.

CREATE TABLE partner_settlement_config (
    -- BIGSERIAL surrogate, engine-managed — same id strategy as partner_kyb
    -- (V011): rows are minted fresh on every SCD-6 write and nothing joins on
    -- their ids from outside this service.
    id               BIGSERIAL   NOT NULL,

    -- FK to the partners surrogate (V003/V004 BIGINT PK). References the
    -- partner AGGREGATE via whichever row was current at write time; consumers
    -- resolve partners by partner_code (same note as V009/V011).
    partner_id       BIGINT      NOT NULL,

    -- Settlement cycle in BUSINESS days after the value date (T+N). 0 = same
    -- business day, 5 = the contractual ceiling for the launch corridors.
    cycle_t_plus_n   INT         NOT NULL DEFAULT 1,

    -- Daily cutoff, a wall-clock time-of-day in cutoff_timezone. Transactions
    -- at or before the cutoff keep the local business date as their value
    -- date; transactions after it book to the next date. 16:30 KST is the KR
    -- banking same-day wire cutoff.
    cutoff_time      TIME        NOT NULL DEFAULT '16:30',

    -- IANA zone id the cutoff is evaluated in (40 chars fits every tz db id,
    -- e.g. 'America/Argentina/ComodRivadavia'). Validated server-side via
    -- ZoneId.of — the DB stores the string.
    cutoff_timezone  VARCHAR(40) NOT NULL DEFAULT 'Asia/Seoul',

    -- Payout rail (V013 CHECK roster below). NOT NULL — a settlement config
    -- without a rail is meaningless, so the wizard must pick one to save
    -- step 4's settlement panel.
    settlement_method VARCHAR(20) NOT NULL,

    -- Business time (ADR-010). Half-open [valid_from, valid_to); NULL valid_to
    -- = open-ended.
    valid_from       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to         TIMESTAMP,

    -- Transaction time (ADR-010). recorded_at defaulted as a safety net but
    -- set explicitly (MICROS-truncated) by the application so paired writes
    -- share one instant; superseded_at is NULL on current rows.
    recorded_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at    TIMESTAMP,

    CONSTRAINT pk_partner_settlement_config PRIMARY KEY (id),

    CONSTRAINT fk_partner_settlement_config_partner
        FOREIGN KEY (partner_id) REFERENCES partners (id),

    CONSTRAINT ck_partner_settlement_config_cycle CHECK (
        cycle_t_plus_n BETWEEN 0 AND 5
    ),

    CONSTRAINT ck_partner_settlement_config_method CHECK (
        settlement_method IN ('SWIFT_MT103', 'KR_FIRM_BANKING', 'BAKONG',
                              'NAPAS_247', 'PROMPT_PAY', 'FAST_SG', 'OTHER')
    )
);

-- Hot-path lookup: "current settlement config for partner P" filters on
-- partner_id = ? AND superseded_at IS NULL — the composite serves it on both
-- engines without partial-index emulation (same choice as V009/V011).
CREATE INDEX idx_partner_settlement_config_current
    ON partner_settlement_config (partner_id, superseded_at);
