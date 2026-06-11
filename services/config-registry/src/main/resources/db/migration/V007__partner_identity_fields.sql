-- V007: Slice 1 Identity-step columns on the partners aggregate.
--
-- WHY
-- ---
-- Slice 1 (docs/PARTNER_SETUP_PLAN.md §"Slice 1 — Identity + Foundation") adds the
-- first wizard step ("Identity"): the legal-name pair, tax-id discriminator,
-- country of incorporation, legal form, registered/operating addresses and the
-- optional LEI. These are the foundation columns every later slice keys off — the
-- KYB/banking/credentials work all joins back to the legal entity captured here.
--
-- EXPAND DISCIPLINE (ADR-013)
-- ---------------------------
-- This migration is Expand-phase only: every column is added NULL-able with no
-- backfill, no NOT NULL tightening, no default that materially changes the row's
-- semantics. The existing GMEREMIT / SENDMN seed rows keep working (their identity
-- columns are left NULL until an operator visits the wizard and saves Step 1).
-- A future Backfill + Contract migration tightens mandatory-at-activation columns
-- to NOT NULL once every live partner has filled them in.
--
-- BITEMPORAL STORAGE (ADR-010)
-- ----------------------------
-- The partners table is already SCD-6 (V004): rows are never UPDATE'd in place,
-- every change is a paired (UPDATE prior SET superseded_at) + (INSERT new) inside
-- one transaction. ADDing columns does not change that contract — the new columns
-- ride along with every future INSERT just like the existing settlement_currency /
-- settlement_rounding_mode columns do.
--
-- COMPATIBILITY
-- -------------
-- PostgreSQL + H2 (PostgreSQL mode) portable DDL. CHAR(2) maps cleanly to both;
-- VARCHAR length limits are advisory at the DB layer — server-side validation
-- (PartnerValidator) enforces the field-level format rules (ISO-3166 alpha-2,
-- ISO 17442 LEI checksum, tax-id discriminator by type, etc.) before any write.

-- Legal name pair: the local-script legal name (e.g. Korean / Cambodian / Vietnamese
-- script) and the Romanized form. Both are nullable here so the wizard can save
-- Step 1 progressively; Slice 8's activation gate is what eventually requires both.
ALTER TABLE partners ADD COLUMN legal_name_local VARCHAR(200);
ALTER TABLE partners ADD COLUMN legal_name_romanized VARCHAR(200);

-- Tax identifier + its discriminator. The discriminator selects the format-validator
-- branch in PartnerValidator (KR_BRN \d{10}, KH_VAT, VN_MST, SG_UEN, GENERIC).
ALTER TABLE partners ADD COLUMN tax_id VARCHAR(40);
ALTER TABLE partners ADD COLUMN tax_id_type VARCHAR(20);

-- ISO-3166 alpha-2 country code of the partner's legal incorporation jurisdiction.
-- CHAR(2) is the canonical storage shape for ISO-3166 alpha-2.
ALTER TABLE partners ADD COLUMN country_of_incorporation CHAR(2);

-- Legal form enum string: CORP | LLC | MTO | EMI | BANK | OTHER. Stored as VARCHAR
-- (not a PG ENUM) so adding new values later does not require a separate ALTER TYPE
-- step and the same DDL runs against H2 unchanged.
ALTER TABLE partners ADD COLUMN legal_form VARCHAR(20);

-- Registered address: the address on the legal-entity formation document. Structured
-- (street1, street2, city, state, postcode, country) rather than a single free-form
-- field so downstream regulatory reporting (BOK / KoFIU / PIPA cross-border PII
-- notice) can pull the discrete components without re-parsing.
ALTER TABLE partners ADD COLUMN registered_street1 VARCHAR(200);
ALTER TABLE partners ADD COLUMN registered_street2 VARCHAR(200);
ALTER TABLE partners ADD COLUMN registered_city VARCHAR(100);
ALTER TABLE partners ADD COLUMN registered_state VARCHAR(100);
ALTER TABLE partners ADD COLUMN registered_postcode VARCHAR(20);
ALTER TABLE partners ADD COLUMN registered_country CHAR(2);

-- Operating address: where the business actually operates from. Often differs from
-- the registered address for partners with multi-jurisdiction footprints.
ALTER TABLE partners ADD COLUMN operating_street1 VARCHAR(200);
ALTER TABLE partners ADD COLUMN operating_street2 VARCHAR(200);
ALTER TABLE partners ADD COLUMN operating_city VARCHAR(100);
ALTER TABLE partners ADD COLUMN operating_state VARCHAR(100);
ALTER TABLE partners ADD COLUMN operating_postcode VARCHAR(20);
ALTER TABLE partners ADD COLUMN operating_country CHAR(2);

-- Legal Entity Identifier per ISO 17442. CHAR(20) because every conformant LEI is
-- exactly 20 alphanumeric characters: 4-char LOU prefix + 2 reserved zeros + 12-char
-- entity-specific component + 2-char mod-97-10 checksum. Optional but tracked — many
-- non-financial partners will not have one, so this column stays NULL for them.
ALTER TABLE partners ADD COLUMN lei CHAR(20);
