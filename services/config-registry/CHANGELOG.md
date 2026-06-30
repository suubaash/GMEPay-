# config-registry — CHANGELOG

All notable changes to the config-registry service. Newest first.

## 2026-06-30 — Cloud-agnostic vault config (agent/cloud-audit)

### Added
- **Document vault (ADR-006) is now fully config-driven across S3 providers.**
  lib-vault's `VaultProperties` gains `region` (`GMEPAY_VAULT_REGION`, default
  `us-east-1`) and `path-style` (`GMEPAY_VAULT_PATH_STYLE`, default `true`). The same
  `io.minio` S3-API client (NOT a cloud SDK) now works against self-hosted MinIO
  (on-prem default, path-style), AWS S3 (virtual-host, real region), or an
  Azure-via-S3 gateway by varying only env. Credential env names normalized to
  `GMEPAY_VAULT_ACCESS_KEY` / `GMEPAY_VAULT_SECRET_KEY` (Spring relaxed binding).
  application.properties comment block updated with the full env contract. Additive;
  MinIO + path-style remain the local/compose default — docker-compose flow unchanged.

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
