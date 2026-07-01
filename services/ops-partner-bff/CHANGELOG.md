# ops-partner-bff — CHANGELOG

All notable changes to the Ops/Partner BFF. Newest first.

## 2026-07-01 — Ops control-tower + 360° search + audited operator actions

Adds the Operations wave BFF surface: one composed situational view, a transaction
search proxy, and thin operator-action proxies that write an operator-action audit
record before delegating. All new upstream adapters follow the established gated
pattern (`@ConditionalOnProperty` `rest` `@Primary` + in-memory stub, `matchIfMissing`).

### Added
- **Control tower** — `GET /v1/admin/ops/control-tower` (`ControlTowerController`) →
  one `ControlTowerView` composed from the gated clients: in-flight txn count +
  UNCERTAIN/aged count (transaction-mgmt search-by-status), webhook backlog
  (PENDING+DLQ), per-partner float headroom + the lowest/at-risk partner (prefunding),
  scheme/partner health rollup (system-health), open reconciliation exceptions
  (settlement-reconciliation), and the current `OperationalStatusView` (config-registry
  ops). Each section is composed in its own try/catch — an unavailable upstream shows
  that section as "unknown" (null counts) and lands in `degradedSections`, never a 500.
- **360° transaction search** — `GET /v1/admin/transactions/search` (`OpsTransactionController`)
  proxies transaction-mgmt `GET /v1/transactions/search`, returning the mapped result page.
- **Audited operator-action endpoints** — each writes an `OperatorActionAuditClient`
  record (who/what/when/reason) BEFORE delegating:
  - `POST /v1/admin/ops/{pause,resume,maintenance,suspend,unsuspend}` → config-registry
    (`OpsActionController` + `OpsControlClient`)
  - `POST /v1/admin/transactions/{ref}/resolve` → transaction-mgmt (`OpsTransactionController`)
  - `POST /v1/admin/webhooks/{id}/replay` → notification-webhook (`OpsWebhookActionController`
    + `WebhookOpsClient`)
  - `POST /v1/admin/settlements/recon/rerun` → settlement-reconciliation (`OpsActionController`)
  RBAC guard: when the `X-Gme-Permissions` header is present it must contain `ops:operate`
  (403 otherwise); absent header = allowed (local dev / gate off), matching the
  internal-auth convention.
- New gated clients: `OpsControlClient` (+ Rest/Stub), `WebhookOpsClient` (+ Rest/Stub),
  `OperatorActionAuditClient` (+ Rest/Stub — the WRITE side of the audit trail, POSTs to
  auth-identity, best-effort/never-blocks). Additive default methods on existing clients:
  `TransactionMgmtClient.search/resolve`, `SettlementClient.openReconExceptions/rerunRecon`.
- `application.properties`: `gmepay.{ops-control,webhook-ops,operator-action-audit}.client`
  selectors (default `stub`) + `gmepay.notification-webhook.base-url`.
- Tests (21): `ControlTowerControllerTest` (compose + a degraded section), `OpsActionControllerTest`
  (each action delegates AND records audit + RBAC guard + 400s), `OpsTransactionControllerTest`
  (search proxy + resolve audit), `OpsWebhookActionControllerTest`, and MockRestServiceServer
  `RestOpsControlClientTest` / `RestWebhookOpsClientTest`.

### Outstanding
1. `notification-webhook` backlog gauge endpoint (`GET /v1/webhooks/deliveries/backlog`)
   + `settlement-reconciliation` recon-exceptions endpoint are the assumed rest contracts;
   confirm shapes when those upstreams land.
2. No Spring Security in the BFF — the RBAC guard is a header check, not a `@PreAuthorize`.
   Harden once the platform's PDP fronts the BFF.
3. In-flight count fans out one search-per-status; a single upstream count-by-status facet
   would collapse it to one call.

## 2026-06-30 — REST clients for revenue-ledger + system-health (P0 gap-closure)

Closes the last two synthetic-data gaps among the operational upstream adapters.
Before this change `RestTransactionMgmtClient` and `RestSettlementClient` already
existed; only revenue and system-health were stub-only.

### Added
- `RestRevenueLedgerClient` (`gmepay.revenue-ledger.client=rest`, `@Primary`,
  `@ConditionalOnProperty`). Maps revenue-ledger `GET /v1/revenue`
  (`RevenueSummaryResponse`) onto the BFF `RevenueSummary`:
  `totalServiceChargeAmount` → fee, `totalFxMarginUsd` → margin, sum → total.
  Upstream is per-(numeric)partnerId, so the system-wide summary is backed by a
  single configured partner (`gmepay.revenue-ledger.aggregate-partner-id`); when
  unset it degrades to an HONEST zero summary + empty breakdown (no synthetic
  data). Tolerates a future `totalRoundingUsd` field via `@JsonIgnoreProperties`.
- `RestSystemHealthClient` (`gmepay.system-health.client=rest`, `@Primary`,
  `@ConditionalOnProperty`). Fans out concurrently to each of the 17 backend
  services' `/actuator/health`, resolving each base URL from
  `gmepay.<service>.base-url` (compose-DNS default). Maps actuator
  `UP`/`DOWN`/`OUT_OF_SERVICE` → `UP`/`DOWN`/`DEGRADED`; unreachable/erroring →
  `DOWN`; indeterminate body → `UNKNOWN`. Per-probe 3s timeout; a slow/failed
  probe never fails the whole snapshot.
- Tests: `RestRevenueLedgerClientTest` (5), `RestSystemHealthClientTest` (3) —
  `MockRestServiceServer`-bound `RestClient`, no live upstream.

### Changed
- `StubRevenueLedgerClient` / `StubSystemHealthClient` now `@ConditionalOnProperty`
  (`havingValue=stub`, `matchIfMissing=true`) so exactly one bean wins, matching
  the transaction-mgmt / settlement adapter pattern.
- `application.properties`: added `gmepay.revenue-ledger.client`,
  `gmepay.revenue-ledger.aggregate-partner-id`, `gmepay.system-health.client`
  selectors; removed the stale "revenue-ledger has no rest client" note.

### Outstanding (INTEGRATION REQUESTS — see build report)
1. revenue-ledger: add `total_rounding_usd` to `RevenueSummaryResponse`.
2. revenue-ledger / config-registry: partner CODE → numeric id mapping so the BFF
   can query per-partner revenue without a hand-configured numeric id.
3. revenue-ledger: a system-wide / multi-axis (by-partner/scheme/currency) revenue
   endpoint to back the Admin dashboard summary + breakdown without stubs.
