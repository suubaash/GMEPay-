# config-registry — CHANGELOG

All notable changes to the config-registry service. Newest first.

## 2026-07-01 — Ops kill-switch (global pause / maintenance / suspend) (ops/config-registry)

### Added
- **Persistence** (`V038__ops_control.sql`, additive/new): `ops_control` — a
  singleton row (id = 1, CHECK-pinned, seeded all-clear) holding `system_paused`
  + `maintenance_mode` global flags plus `reason`/`since`/`updated_by`/`updated_at`;
  and `ops_suspension` — per-entity emergency quarantine rows
  (`entity_type PARTNER|SCHEME|ROUTE` CHECK, `entity_id`, `reason`, `active`,
  `created_by`/`created_at`). Plain portable DDL (TIMESTAMP/BOOLEAN, no
  TIMESTAMPTZ/JSONB).
- **Read** `GET /v1/ops/operational-status` → shared
  `com.gme.pay.contracts.OperationalStatusView`; aggregates the control flags +
  active suspensions into suspendedPartners/Schemes/Routes; returns
  `OperationalStatusView.allClear()` when nothing is set.
- **Operator actions** (single-operator + immediate — emergency, NOT 4-eyes;
  every action hash-chain audited via the existing `AuditLogService`, ADR-007,
  with `X-Actor` operator + reason): `POST /v1/ops/pause`, `/resume`,
  `/maintenance` {on,reason}, `/suspend` {entityType,entityId,reason},
  `/unsuspend`. All idempotent (audit row written only on real state change).
  Global actions chain under `ops-control`/`global`; per-entity under
  `ops-suspension`/`TYPE:id`.
- Tests: `OpsControlServiceTest` (8) + `OpsControlControllerTest` (4) cover
  pause→paused, suspend→bucket, unsuspend→clear, idempotency, per-action audit
  row, status aggregation, bad-entityType 400.

## 2026-07-01 — partner_scheme QR network_identifier (ADR-016) (fo/config-registry)

### Added
- **`partner_scheme.network_identifier`** (`V037__partner_scheme_network_identifier.sql`):
  nullable `VARCHAR(200)`, a COMMA-SEPARATED list of the QR network GUID(s) a scheme fronts
  (ADR-016 QR-classified failover routing). Additive/Expand-safe ALTER ADD COLUMN. Back-fills
  current rows: `ZEROPAY → com.zeropay`, `NEPAL →
  fonepay.com,nepalpay,khalti,mobank,unionpay,smartqr` (guarded `IS NULL`, idempotent).
- **`PartnerSchemeEntity`** maps the column and derives it from `scheme_id` in `onPersist`
  (same map as the V037 back-fill) so rows INSERTed via the frozen write command — which has
  no `networkIdentifier` field — still carry it. Exposed (null-safe) through both read
  surfaces: `toView` (`GET /v1/admin/partners/{code}/schemes`, step-7 patch response) and
  `toLocationView` (`GET /v1/schemes/resolve`), populating the ADR-016 field already present
  on `PartnerSchemeView`.
- Tests: seeded ZEROPAY/NEPAL rows expose the expected identifier via both paths; each NEPAL
  network GUID is discoverable by CSV membership; an unmapped scheme stays null.
- config-registry only STORES + EXPOSES; the network→candidate filter/ordering lives in
  smart-router.

## 2026-07-01 — NEPAL scheme registration (na/wiring)

### Added
- **NEPAL in the scheme catalog** (`SchemeCatalogService`, `GET /v1/schemes`): operating
  country `NP`, currency `NPR`, `LIVE`/`ACTIVE` — a second live-adapter scheme beside
  ZEROPAY (a live `scheme-adapter-nepal` ships alongside). Supported Nepal networks
  (khalti/mobank/fonepay/nepalpay/unionpay/smartqr) are carried descriptively in `name`
  (the DTO has no networks field). Slotted right after ZEROPAY.
- **`NEPAL` added to the V022 `ck_partner_scheme_scheme` CHECK roster** so partner-scheme
  rows may enable it. `PartnerSchemeService.SCHEMES` auto-picks it up (derives from
  `SchemeCatalogService.schemeIds()`), keeping the catalog / enablement / DB-CHECK
  invariant intact (asserted by `SchemeCatalogServiceTest`).
- Tests updated: catalog now has 8 rows with two ACTIVE schemes (ZEROPAY, NEPAL).

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
