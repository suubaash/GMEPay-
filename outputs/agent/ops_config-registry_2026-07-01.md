> 작업: Ops kill-switch (config-registry) / 출처: agent

# Ops kill-switch — config-registry

Global pause / maintenance mode + emergency per-entity suspend/quarantine, on branch `ops/config-registry` (off `ops/contracts`). Edits confined to `services/config-registry/`.

## Endpoints (`OpsControlController`, `/v1/ops`)
- `GET  /operational-status` → shared `com.gme.pay.contracts.OperationalStatusView`; aggregates flags + active suspensions; `allClear()` shape when nothing set.
- `POST /pause` {reason} — engage master kill switch.
- `POST /resume` — release it.
- `POST /maintenance` {on, reason} — soft degraded flag.
- `POST /suspend` {entityType(PARTNER|SCHEME|ROUTE), entityId, reason}.
- `POST /unsuspend` {entityType, entityId}.

All actions single-operator + immediate (emergency, NOT 4-eyes — a kill switch must pull now), idempotent, return the resulting `OperationalStatusView`. Operator via `X-Actor` header (same convention as `PartnerLifecycleController`); client IP via `X-Forwarded-For`. Bad entityType → 400.

## Persistence (`V038__ops_control.sql`, new — no in-place edit)
- `ops_control` — singleton row (id=1, CHECK-pinned, seeded all-clear): `system_paused`, `maintenance_mode`, `reason`, `since`, `updated_by/at`.
- `ops_suspension` — `entity_type` CHECK PARTNER|SCHEME|ROUTE, `entity_id`, `reason`, `active`, `created_by/at`; unsuspend toggles `active=false` (history retained). Read aggregates only `active=true`.
- Portable DDL (TIMESTAMP/BOOLEAN, no TIMESTAMPTZ/JSONB), additive-only.

## Audit wiring
Reuses existing `AuditLogService.publish` (ADR-007 hash chain). Every real state change writes one row with operator + reason; idempotent no-ops write nothing. Global actions chain under `ops-control`/`global` (events OPS_PAUSED/RESUMED/MAINTENANCE_ON/OFF); per-entity under `ops-suspension`/`TYPE:id` (OPS_SUSPENDED/UNSUSPENDED). 4-eyes change-request flow deliberately bypassed for emergency speed.

## Test status
`./gradlew :services:config-registry:test` → BUILD SUCCESSFUL. New: `OpsControlServiceTest` (8) + `OpsControlControllerTest` (4), all green; full suite unbroken. Covers pause→systemPaused=true, suspend PARTNER→bucket, unsuspend→clear, idempotency (no dup audit), per-action audit row, multi-bucket aggregation, re-suspend, 400.

## RBAC note
No in-process RBAC exists in config-registry (enforced at BFF/Keycloak edge, per `AuditLogController`), so guarding is left to the BFF ops-role gate; operator identity is carried + audited via `X-Actor`.

## Remaining (≤3)
1. BFF `/v1/admin/ops/*` passthrough + Keycloak ops-role gate (other service — FROZEN here).
2. Enforcement: smart-router / payment-executor should consult `operational-status` to actually reject on pause/suspend (downstream, not this service).
3. Optional maintenance-window auto-expiry / scheduled resume (deferred).
