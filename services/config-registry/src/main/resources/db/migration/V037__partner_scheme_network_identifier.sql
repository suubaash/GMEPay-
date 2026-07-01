-- V037: partner_scheme.network_identifier — ADR-016 (QR-classified failover routing).
--
-- WHY
-- ---
-- A scanned merchant QR carries a globally-unique network identifier (EMVCo
-- Merchant Account Information sub-tag 00 reverse-domain / AID, or a JSON
-- structural class): com.zeropay, fonepay.com, a NepalPay GUID, etc. ADR-016
-- makes THAT GUID — not the country — the deterministic routing key. This
-- column is the config-registry side of the map: which QR network(s) a given
-- partner_scheme row can front. smart-router (built in parallel) does the
-- network -> ordered-candidate FILTERING; config-registry only STORES + EXPOSES.
--
-- SHAPE
-- -----
-- A partner/scheme may front MULTIPLE QR networks, so this is a COMMA-SEPARATED
-- list of GUIDs (membership test, not equality). Nullable / additive per
-- ADR-013 Expand discipline — an in-place ALTER that only ADDs a nullable
-- column is Expand-safe (no existing reader breaks; existing rows read NULL
-- until populated). VARCHAR width 200 comfortably holds the seeded NEPAL CSV
-- (six short tokens) with head-room.
--
-- POPULATION
-- ----------
-- Back-fill the two live-adapter schemes' CURRENT rows (superseded_at IS NULL)
-- that have not been populated yet. Guarded on network_identifier IS NULL so
-- the UPDATE is idempotent and never clobbers an operator-set value. The same
-- scheme_id -> network map is mirrored in PartnerSchemeEntity.onPersist so rows
-- INSERTed AFTER this migration (the write command has no networkIdentifier
-- field — the contract is frozen) also carry the identifier.
--
-- COMPATIBILITY
-- -------------
-- Plain ALTER ADD COLUMN — parses identically on PostgreSQL and H2 (PG-mode),
-- same as every other additive column migration in this chain.

ALTER TABLE partner_scheme
    ADD COLUMN network_identifier VARCHAR(200);

-- Back-fill current rows of the two schemes with a live adapter today.
UPDATE partner_scheme
    SET network_identifier = 'com.zeropay'
    WHERE scheme_id = 'ZEROPAY'
      AND superseded_at IS NULL
      AND network_identifier IS NULL;

UPDATE partner_scheme
    SET network_identifier = 'fonepay.com,nepalpay,khalti,mobank,unionpay,smartqr'
    WHERE scheme_id = 'NEPAL'
      AND superseded_at IS NULL
      AND network_identifier IS NULL;
