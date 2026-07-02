# ops-partner-bff — CHANGELOG

All notable changes to the Ops/Partner BFF. Newest first.

## 2026-07-02 — Customer-support transaction read surface

Additive. Turns the Ops transaction proxy into a usable customer-support read surface and
stops CS reads from needing the dangerous `ops:operate` permission. Libs + other services
untouched.

### Added
- **Customer-search pass-through** — `GET /v1/admin/transactions/search` now forwards two new
  optional params to transaction-mgmt's `GET /v1/transactions/search`: `userRef` (the end-customer
  / wallet id) and `reference` (the partner's own reference). A support agent can now find every
  transaction of one customer, or look up by the partner's reference. The previously **silently
  dropped `q`** is retained as the free-text term (transaction-mgmt matches it against `txnRef`) —
  `q`, `status`, `partnerId` all still forwarded; the mystery-drop is gone.
- **New CS response fields** — transaction search/detail now surface `failureReason`,
  `statusLabel` (plain language), `declineReasonText`, and an ordered `statusHistory`
  (`{status, statusLabel, at, note}`) mapped from transaction-mgmt. All null-safe for older
  transactions (`@JsonInclude(NON_NULL)`), so pre-existing txns/consumers are unaffected.
  `StatusEntry` enriched from `{status, at}` to `{status, statusLabel, at, note}` (back-compat
  `StatusEntry.of(status, at)` factory retained).

### Changed (security)
- **Support-scoped read auth** — the CS **read** endpoints (`GET /v1/admin/transactions/search`,
  `.../transactions`, `.../transactions/recent`, `.../transactions/{id}`) are now gated on the
  support-appropriate **`txn.view`** permission (fail-closed via `OpsRbacGuard.requireTxnView`)
  instead of the money/state-affecting `ops:operate`. `ops:operate` still implies read access.
  The `ops:operate` gate on state-changing actions (transaction resolve, pause/resume,
  maintenance, alert-ack, etc.) is **unchanged**. Net: `txn.view` ⇒ look up + read a transaction;
  `ops:operate` still required for force-resolve / kill-switch.

## 2026-07-02 — Wire ops alerts to real on-call paging

Closes the paging gap: `gmepay.ops.alert` was consumed into an in-memory store + exposed at
`GET /v1/admin/ops/alerts`, but nothing paged a human. Now a consumed alert can page the
on-call, be acknowledged, and (optionally) escalate. Additive; no lib or other service touched.

### Added
- **Vendor-agnostic paging port** (ADR-015, no cloud SDK) — `PagingPort.page(PageRequest)`
  with a small stable wire shape (`alertType, severity, subjectRef, detail, occurredAt, link`).
  - `WebhookPagingAdapter` — active when `gmepay.ops.paging.webhook-url` is set; POSTs the
    alert as JSON to one generic on-call webhook (works with Slack incoming webhooks /
    PagerDuty Events API / Opsgenie / MS Teams — no vendor hardcoded). Explicit connect+read
    timeout (`gmepay.ops.paging.timeout-ms`, default 3000ms) and retries on 5xx/transport
    error (`gmepay.ops.paging.max-attempts`, default 3); 4xx = permanent, not retried. Never throws.
  - `LogPagingAdapter` — `@ConditionalOnMissingBean` fallback (via `PagingConfig`) that logs
    the page, so paging is functional with zero config.
- **Dispatch on consume** — `OpsPagingDispatcher.onStored(...)`, called by
  `OpsAlertEventHandler` after storing: pages only when `severity >=
  gmepay.ops.paging.min-severity` (default `CRITICAL`; can be lowered to `WARN`); INFO/below
  stored only. Single-fire across replicas via the Kafka consumer group.
- **Dedupe / cooldown** — suppresses a repeat page for the same `(alertType + subjectRef)`
  within `gmepay.ops.paging.dedupe-window` (default 15m); suppressed pages recorded as
  `SUPPRESSED`.
- **Delivery record** — each attempt (`DELIVERED` / `FAILED` / `SUPPRESSED`, channel,
  attempts, lastAt) stamped on the stored alert (`OpsAlertView.Paging`) and returned by
  `GET /v1/admin/ops/alerts`.
- **Acknowledge API** — `POST /v1/admin/ops/alerts/{id}/ack` {operator, note} marks the alert
  `acked` (stops escalation), reflected in the alerts list + control tower. Fail-closed
  `ops:operate` RBAC (`OpsRbacGuard`) + durable operator-action audit (`ops.alert.ack`), same
  as the other ops actions.
- **Escalation (config-gated, default OFF)** — `OpsPagingEscalationScheduler` re-pages
  un-acked CRITICAL alerts older than `gmepay.ops.paging.escalation.after` (default 10m) on a
  sweep. Enabled only by `gmepay.ops.paging.escalation.enabled=true`. **Single-replica-only:**
  ops-partner-bff has NO DataSource, so there is no ShedLock guard — run on exactly one
  replica; cooldown still bounds duplicate pages. Documented to ShedLock-guard if a DataSource
  is ever added.

## 2026-07-02 — Harden Ops: fail-closed RBAC + fail-closed audit + ops.alert consumer

Closes defect #2 (RBAC failed open + best-effort audit on money-affecting operator
actions) and defect #5 (`gmepay.ops.alert` had emitters but no consumer). Additive; no
other service or lib touched.

### Changed
- **Fail-CLOSED RBAC** (`OpsRbacGuard`, new `@Component`) — the ops operator-action guard
  now DENIES (403) when `X-Gme-Permissions` is absent or lacks `ops:operate`. The previous
  allow-when-absent behaviour (which let pause/suspend/resolve/recon-rerun/webhook-replay run
  unauthorized) is removed. Config-overridable only via the dev flag `gmepay.ops.rbac.enforce`
  (default `true` = enforce); with the gate off an *absent* header is allowed but a
  *present-but-wrong* header is still denied. `OpsActionController.guard(...)` (static,
  allow-when-absent) deleted; all three action controllers inject the guard.
- **Fail-CLOSED audit for money-affecting actions** — `OperatorActionAuditClient.recordDurable(...)`
  added; it throws `AuditWriteException` (mapped to HTTP 500 via `@ResponseStatus`) when the
  record cannot be durably persisted. The money/state-affecting actions (pause/resume/
  maintenance/suspend/unsuspend, transaction resolve, webhook replay, recon rerun) now call
  `recordDurable` BEFORE delegating — if the audit write fails the action FAILS (5xx) and the
  upstream is NOT called. `record(...)` (best-effort) stays for pure reads. The live REST
  client fails closed on `recordDurable`; the in-memory stub is always durable.

### Added
- **`gmepay.ops.alert` consumer (#5)** — `OpsAlertKafkaConsumer` + gated
  `OpsAlertKafkaConsumerConfig` (`@ConditionalOnProperty("spring.kafka.bootstrap-servers")`,
  MANUAL ack, `.DLT` poison handling), mirroring the revenue-ledger/notification-webhook
  pattern. No broker ⇒ no listener beans (no-broker fallback). `OpsAlertEventHandler`
  deserializes the canonical `OpsAlertPayload` and stores it in the in-memory, bounded,
  newest-first `OpsAlertStore` (`gmepay.ops.alerts.capacity`, default 200).
- **`GET /v1/admin/ops/alerts`** (`OpsAlertController`) — recent alerts newest-first, filter
  by `severity` and/or `type`, `limit` (default 100).
- **Control tower alert strip** — `ControlTowerView.recentAlerts` (total, critical count,
  newest 10) folded in from the store.

Follow-ups: durable (JPA) alert store; a real pager / on-call push.

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
