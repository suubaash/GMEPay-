-- V041: extend the partner_scheme scheme roster with SENDMN + NINEPAY
-- (QR scheme accommodation plan, Phase 2 hub wiring).
--
-- WHY
-- ---
-- Two new schemes join the platform roster:
--   * SENDMN  — SendMN QR (Mongolia, QPay-fronted). scheme-adapter-sendmn is live and
--               payment-executor routes to it (SchemeClientRouter / SendmnRestSchemeClient),
--               so partner enablements must be storable: ACTIVE in the catalog.
--   * NINEPAY — 9Pay (Vietnam payout). scheme-adapter-ninepay exists but hub payout
--               orchestration is deferred (plan decision D4): PLANNED in the catalog,
--               rostered here so an enablement saved ahead of go-live is not rejected.
--
-- The V022 CHECK (ck_partner_scheme_scheme) is a closed roster, so extending it is a
-- drop + re-add with the full list. SchemeCatalogServiceTest pins the catalog roster to
-- THIS constraint's list (it parses the latest migration that re-declares the CHECK), so
-- catalog and DB CHECK cannot drift.
--
-- NETWORK IDENTIFIER BACK-FILL (mirrors V037)
-- -------------------------------------------
-- SENDMN fronts the QPay QR network. PLACEHOLDER identifiers 'qpay,sendmn' — the real
-- QPay EMVCo AID/GUID (believed mn.qpay...) is pending a sample QR from SendMN (open
-- item, QR scheme plan Phase 2); QrSchemeClassifier carries the same placeholders. The
-- same map is mirrored in PartnerSchemeEntity.defaultNetworkIdentifierFor for rows
-- INSERTed after this migration. Guarded on network_identifier IS NULL (idempotent,
-- never clobbers an operator-set value). NINEPAY is a payout rail, not a scanned-QR
-- network, so it gets no identifier.
--
-- COMPATIBILITY
-- -------------
-- ALTER TABLE ... DROP CONSTRAINT / ADD CONSTRAINT parse identically on PostgreSQL and
-- H2 (PG-mode) — engine-neutral, so this lives in db/migration (not db/vendor).

ALTER TABLE partner_scheme
    DROP CONSTRAINT ck_partner_scheme_scheme;

ALTER TABLE partner_scheme
    ADD CONSTRAINT ck_partner_scheme_scheme CHECK (
        scheme_id IN ('ZEROPAY', 'NEPAL', 'BAKONG', 'NAPAS_247', 'PROMPT_PAY',
                      'FAST_SG', 'QRIS', 'KHQR', 'SENDMN', 'NINEPAY')
    );

-- Back-fill current SENDMN rows with the placeholder QPay network identifiers.
UPDATE partner_scheme
    SET network_identifier = 'qpay,sendmn'
    WHERE scheme_id = 'SENDMN'
      AND superseded_at IS NULL
      AND network_identifier IS NULL;
