# config-registry — CHANGELOG

All notable changes to the config-registry service. Newest first.

## 2026-06-30 — Scheme roster drift fix (agent/config-registry)

### Fixed
- **`GET /v1/schemes` catalog now matches the V022 `partner_scheme` DB CHECK roster.**
  The supported-scheme catalog (`SchemeCatalogService`) had drifted to advertise
  `QPAY`, `SBP`, and `PROMPTPAY` — none of which the V022 `ck_partner_scheme_scheme`
  DB CHECK nor the Slice-7 enablement endpoint (`PartnerSchemeService.replaceDraftSchemes`)
  accept, and it omitted three schemes that *are* enableable (`BAKONG`, `NAPAS_247`,
  `FAST_SG`). The Admin UI scheme picker is populated from this catalog, so an operator
  could pick a scheme that the step-7 save then rejected with a 400. The catalog now
  carries exactly the platform-wide roster the V022 CHECK and the BFF
  `StubConfigRegistryClient` already encode: `ZEROPAY, BAKONG, KHQR, NAPAS_247,
  PROMPT_PAY, FAST_SG, QRIS`.

### Changed
- `PartnerSchemeService.SCHEMES` (its accepted enablement roster) now derives from
  `SchemeCatalogService.schemeIds()` rather than a hard-coded parallel list, so the
  picker and the enablement endpoint cannot drift again.

### Tests
- New `SchemeCatalogServiceTest`: pins (1) the catalog leads with the only `ACTIVE`
  scheme, (2) every row is fully populated with BFF-bindable fields, (3) the enablement
  roster equals the catalog roster, and (4) the catalog roster equals the
  `ck_partner_scheme_scheme` roster parsed straight from the V022 migration — locking
  out future drift on either side.
- `SchemeCatalogControllerTest` updated to the corrected 7-scheme catalog.

Build: `./gradlew :services:config-registry:test` — green.
