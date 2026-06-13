-- V012: partner_bank_account child table — Slice 4 "Banking & Settlement"
-- (docs/PARTNER_SETUP_PLAN.md §Slice 4).
--
-- WHY BITEMPORAL (ADR-010)
-- ------------------------
-- Bank coordinates are the highest-risk regulated data the registry holds:
-- payout fraud is almost always an account-swap, so the regulator (and our own
-- settlement-reconciliation) must be able to answer "what account did we
-- believe in on date D, and when did we learn otherwise". The table therefore
-- carries the same SCD-6 column pairs as partners (V004) / partner_contact
-- (V009) / partner_kyb (V011):
--
--   * valid_from  / valid_to       — business time (when the account fact is true)
--   * recorded_at / superseded_at  — transaction time (when we wrote it; NULL on
--                                     rows that are still current)
--
-- Storage discipline: rows are NEVER UPDATEd in place. The wizard's step-4 save
-- is a bulk replace — one transaction that (UPDATE all current rows SET
-- superseded_at = now()) + (INSERT the new set with recorded_at = now()), both
-- halves sharing the same MICROS-truncated instant (see
-- PartnerBankAccountService). A verification verdict is likewise a paired write
-- on the single affected row.
--
-- SCOPE NOTE (Slice 4 vs Slice 8)
-- -------------------------------
-- During ONBOARDING drafts these writes go direct (audited per ADR-007). The
-- 2-authorized-signatory approval flow for POST-ACTIVATION bank changes lands
-- in Slice 8 with the partner FSM — it is intentionally NOT modelled here.
--
-- CONSTRAINTS / INDEXING
-- ----------------------
-- "At most one is_primary per currency per partner" would need a partial unique
-- index (WHERE is_primary AND superseded_at IS NULL) which H2 PG-mode cannot
-- express, so — like the V009 decision to skip partial-index emulation — the
-- rule is enforced server-side in PartnerBankAccountService against the bulk
-- payload (the payload IS the full current state under replace semantics).
-- The hot query is `WHERE partner_id = ? AND superseded_at IS NULL`, served by
-- the plain composite index below on both engines.
--
-- COMPATIBILITY
-- -------------
-- PostgreSQL-compatible DDL that also runs under H2 in PostgreSQL mode:
--   * Plain TIMESTAMP, not TIMESTAMPTZ — H2 PG-mode does not recognise the
--     TIMESTAMPTZ alias (same portable spelling as V004/V006/V009/V011). The
--     JPA boundary stores java.time.Instant, marshalled as UTC on both engines.
--   * BIGSERIAL surrogate id, engine-managed (GenerationType.IDENTITY) — same
--     id strategy as partner_contact (V009).
--   * CHECK ... IN (...) rosters pass NULL on nullable columns on both engines
--     (NULL IN (...) is UNKNOWN, which CHECK treats as pass).
--
-- ADR-013 Expand discipline: wholly-new table; every nullable-able column is
-- nullable or defaulted; no in-place ALTERs on existing tables.

CREATE TABLE partner_bank_account (
    -- BIGSERIAL surrogate, engine-managed (GenerationType.IDENTITY on the
    -- entity) — same id strategy as partner_contact (V009).
    id                            BIGSERIAL    NOT NULL,

    -- FK to the partners surrogate (V003/V004 BIGINT PK). Referential
    -- integrity only — consumers resolve partners by partner_code for the
    -- current view (same note as V009).
    partner_id                    BIGINT       NOT NULL,

    -- ISO-4217 settlement currency of the account (e.g. 'KRW', 'USD').
    -- Shape enforced server-side (^[A-Z]{3}$); the full ISO roster is not
    -- CHECKed — currencies appear/retire without a migration.
    currency                      CHAR(3)      NOT NULL,

    bank_name                     VARCHAR(140) NOT NULL,

    -- BIC-8 or BIC-11 (ISO 9362): ^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$ —
    -- format enforced server-side by PartnerBankAccountService, not the DB.
    -- NULL for domestic rails that route without SWIFT (e.g. KR bank codes).
    bic_swift                     VARCHAR(11),

    -- IBAN (max 34 chars per ISO 13616) OR a raw domestic account number —
    -- KR accounts are not IBAN. The service applies the mod-97 checksum only
    -- when the value is IBAN-shaped (two letters + two digits prefix).
    iban_or_account_number        VARCHAR(34)  NOT NULL,

    account_holder_name           VARCHAR(140) NOT NULL,

    -- ISO-3166 alpha-2 country of the account-holding branch.
    bank_country                  CHAR(2)      NOT NULL,

    -- Optional correspondent/intermediary BIC for cross-border SWIFT payouts.
    intermediary_bic              VARCHAR(11),

    -- Provider-stamped verification verdict; never operator-typed. KFTC's
    -- 계좌실명조회 (account-holder name check) covers KR accounts; BANK_LETTER /
    -- MICRO_DEPOSIT cover overseas rails. Stamped via the verify endpoint
    -- (AccountVerificationProvider port, ADR-009-style seam).
    verification_status           VARCHAR(20)  NOT NULL DEFAULT 'UNVERIFIED',

    -- partner_document (V010) row id of the uploaded evidence (e.g. the bank
    -- letter scan in the DocumentVault). Deliberately NOT a hard FK: documents
    -- are their own SCD-6 aggregate and the reference is to the upload event,
    -- not to whichever document row is current (same loose-reference choice as
    -- partner_kyb.cbddq_doc_id, V011).
    verification_evidence_doc_id  BIGINT,

    -- Date the verification verdict landed; NULL while UNVERIFIED.
    verification_date             DATE,

    -- TRUE for the payout account of record in its currency. At most one per
    -- (partner, currency) among current rows — service-enforced (see header).
    is_primary                    BOOLEAN      NOT NULL DEFAULT FALSE,

    -- SWIFT charge bearer for cross-border payouts; NULL for domestic rails.
    swift_charge_bearer           VARCHAR(3),

    -- What the account is used for. Settlement (Slice 6) reads PAYOUT rows;
    -- prefunding reads FLOAT_TOPUP.
    purpose                       VARCHAR(15)  NOT NULL DEFAULT 'PAYOUT',

    -- Business time (ADR-010). Half-open [valid_from, valid_to); NULL valid_to
    -- = open-ended.
    valid_from                    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to                      TIMESTAMP,

    -- Transaction time (ADR-010). recorded_at is defaulted as a safety net but
    -- the application sets it explicitly (MICROS-truncated) so paired writes
    -- share one instant; superseded_at is NULL on current rows.
    recorded_at                   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    superseded_at                 TIMESTAMP,

    CONSTRAINT pk_partner_bank_account PRIMARY KEY (id),

    CONSTRAINT fk_partner_bank_account_partner
        FOREIGN KEY (partner_id) REFERENCES partners (id),

    CONSTRAINT ck_partner_bank_account_verification_status CHECK (
        verification_status IN ('UNVERIFIED', 'KFTC_VERIFIED', 'BANK_LETTER', 'MICRO_DEPOSIT')
    ),

    CONSTRAINT ck_partner_bank_account_charge_bearer CHECK (
        swift_charge_bearer IN ('OUR', 'BEN', 'SHA')
    ),

    CONSTRAINT ck_partner_bank_account_purpose CHECK (
        purpose IN ('PAYOUT', 'FLOAT_TOPUP', 'REFUND')
    )
);

-- Hot-path lookup: "all current bank accounts for partner P" filters on
-- partner_id = ? AND superseded_at IS NULL. Plain composite (no partial-index
-- emulation needed — no per-partner uniqueness at the index level, see header).
CREATE INDEX idx_partner_bank_account_current
    ON partner_bank_account (partner_id, superseded_at);
