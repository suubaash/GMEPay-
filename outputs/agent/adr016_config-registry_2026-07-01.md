> 작업: ADR-016 config-registry network mapping / 출처: agent

# ADR-016 — config-registry `network_identifier` (store + expose)

## Migration
- **`V037__partner_scheme_network_identifier.sql`** (next free number; V036 was highest,
  V022/V023 already applied — new migration, no in-place edit).
- Adds nullable `network_identifier VARCHAR(200)` to `partner_scheme` (ALTER ADD COLUMN,
  Expand-safe, parses on both PG and H2 PG-mode).

## Populated values (current rows, `IS NULL`-guarded → idempotent)
- `ZEROPAY` → `com.zeropay`
- `NEPAL`   → `fonepay.com,nepalpay,khalti,mobank,unionpay,smartqr`

Same `scheme_id → network` map is mirrored in `PartnerSchemeEntity.onPersist`, so rows
INSERTed after the migration (the frozen write command has no `networkIdentifier` field)
still carry the identifier. Unmapped schemes stay NULL.

## Exposure points
- `PartnerSchemeEntity` — new mapped column + getter/setter; `toView()` and
  `toLocationView()` both pass `networkIdentifier` (null-safe) into the ADR-016 field
  already present on `PartnerSchemeView`.
- Surfaces: `GET /v1/admin/partners/{code}/schemes` + step-7 patch response (`toView`);
  `GET /v1/schemes/resolve` (`toLocationView`).
- Audit canonical (`PartnerSchemeJson`) intentionally unchanged — it records the wiring
  fact set; the identifier is derived-from-scheme, no independent audit info.

## Test status
- `./gradlew :services:config-registry:test` → **BUILD SUCCESSFUL**.
- Added to `PartnerSchemeServiceTest`: seeded ZEROPAY/NEPAL expose expected identifier via
  BOTH read paths; each NEPAL GUID discoverable by CSV membership (+ a non-served GUID does
  not match); unmapped scheme → null.

## Remaining (≤3)
1. smart-router owns the network→candidate filter/ordering (built in parallel; not here).
2. `PartnerSchemeCommand`/write API cannot set `network_identifier` per-row yet (frozen
   contract; entity derives from scheme_id). Operator-set values need a future contract field.
3. `SchemePostgresMigrationIT` (Testcontainers, CI-only) not run locally — no Docker here.
