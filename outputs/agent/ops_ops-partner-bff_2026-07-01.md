> 작업: Ops control-tower + actions (ops-bff) / 출처: agent

# Ops control-tower + 360° search + audited operator actions — ops-partner-bff

Branch `ops/ops-partner-bff` (off `ops/contracts`). Additive only; edits confined to
`services/ops-partner-bff/`. Full module test suite green.

## 1. Control tower — `GET /v1/admin/ops/control-tower`
`ControlTowerController` composes one `ControlTowerView` from the gated rest/stub clients.
Fields:
- `inFlight` — `inFlightCount` (AUTHORIZED+PENDING+PROCESSING) + `uncertainOrAgedCount`
  (UNCERTAIN) via transaction-mgmt search-by-status.
- `webhookBacklog` — `pending` / `dlq` / `total` (notification-webhook).
- `floatHeadroom` — per-partner `{balance, threshold, pctOfThreshold, atRisk}` +
  `lowest` (min pctOfThreshold) via prefunding × config-registry partner list.
- `health` — `{total, up, down, degraded, unhealthy[]}` (system-health fan-out).
- `openReconExceptions` — count (settlement-reconciliation).
- `operationalStatus` — the shared `OperationalStatusView` (config-registry ops; paused/
  maintenance/suspended lists).
- `degradedSections[]` — names of sections whose upstream was unavailable.

**Degrade, never 500:** each section has its own try/catch; an unavailable upstream →
"unknown" (null counts) + section name in `degradedSections`, HTTP 200 preserved.

## 2. 360° search — `GET /v1/admin/transactions/search`
`OpsTransactionController.search` proxies transaction-mgmt `GET /v1/transactions/search`
(`q`, `status`, `partnerId`, `page`, `size`) → mapped `Page<TransactionSummary>`.

## 3. Audited operator actions (audit-record BEFORE delegating)
Each writes an `OperatorActionAuditClient` record (action/target/actor=`X-Gme-Principal-Id`/
reason) then delegates:
- `POST /v1/admin/ops/{pause,resume,maintenance,suspend,unsuspend}` → config-registry (`OpsControlClient`)
- `POST /v1/admin/transactions/{ref}/resolve` → transaction-mgmt (`resolve`)
- `POST /v1/admin/webhooks/{id}/replay` → notification-webhook (`WebhookOpsClient`)
- `POST /v1/admin/settlements/recon/rerun` → settlement-reconciliation (`rerunRecon`)

Audit is a write-side client (Rest → auth-identity `POST /v1/audit/operator-actions`,
best-effort/never blocks the action; Stub captures in memory for tests). RBAC guard:
`X-Gme-Permissions` present ⇒ must contain `ops:operate` (403), absent ⇒ allowed (dev/gate-off).

All new clients gated (`rest` @Primary + `stub` matchIfMissing): `OpsControlClient`,
`WebhookOpsClient`, `OperatorActionAuditClient`. Existing clients extended additively
(`TransactionMgmtClient.search/resolve`, `SettlementClient.openReconExceptions/rerunRecon`).

## Test status
`./gradlew :services:ops-partner-bff:test` — BUILD SUCCESSFUL, whole module green.
21 new tests: ControlTower(2, incl. degraded section), OpsAction(9, delegate+audit+RBAC+400),
OpsTransaction(3), OpsWebhook(1), RestOpsControl(3), RestWebhookOps(3) — MockMvc + MockRestServiceServer.

## Remaining (≤3)
1. notification-webhook backlog + settlement-reconciliation recon-exceptions endpoint
   shapes are assumed rest contracts; confirm when those upstreams land.
2. RBAC guard is a header check (no Spring Security in BFF); harden behind the platform PDP.
3. In-flight count is one search-per-status; a count-by-status facet upstream would make it one call.
