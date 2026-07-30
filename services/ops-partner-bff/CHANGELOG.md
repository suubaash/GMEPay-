# ops-partner-bff — CHANGELOG

All notable changes to the Ops/Partner BFF. Newest first.

## 2026-07-30 - The BFF gains a datastore, and the last single-replica ceiling in the platform is gone

`OpsAlertStore` was a 200-entry per-JVM `ArrayDeque` with a per-JVM `AtomicLong` minting the alert
ids. It was the ONE thing keeping this service at one replica.

### Added
- **This service now owns a small database** - a deliberate, narrow exception to its "owns no data"
  rule. Only state that is *born* here: `ops_alerts` (Flyway **V001**), `operator_action_audit`
  (**V002**), `shedlock` (**V003**). Everything else the BFF shows is still read over HTTP from the
  service that owns it. `spring-boot-starter-data-jpa` + `flyway-core` + `postgresql`/`h2`, and
  `shedlock-spring`/`-provider-jdbc-template` for one locked job.
- **`alert/OpsAlertStore` is now a port** with `JpaOpsAlertStore` (default) and
  `InMemoryOpsAlertStore`, selected by `gmepay.ops.alerts.store` = `db` | `memory`; anything else
  **refuses to start**, and `db` over an in-memory H2 logs a WARN naming the N=1 ceiling it silently
  reimposes.
- **`alert/OpsAlertRetentionSweeper`** - `gmepay.ops.alerts.retention-days` (default 90, the same
  property name and default as payment-executor's emitter-side `ops_alerts` so the two halves of one
  alert's history age out together). An ENGINEERING default: a records-retention owner should confirm
  how long operational alert evidence and the acks on it must be kept.
- **`client/db/DbOperatorActionAuditClient`** - the durable operator-action audit trail, and the new
  default.
- Tests: `JpaOpsAlertStoreTest` (two replicas, restart, the concurrent paging-stamp-vs-ack race),
  `OpsAlertStoreConfigTest`, `EscalationSweepAcrossReplicasTest`,
  `OperatorActionAuditClientSelectionTest`, `DbOperatorActionAuditClientTest`. Every cross-replica
  assertion is paired with the per-JVM version getting it wrong.

### Fixed
- **The alert IDS collided at N>1**, which nobody had written down: `seq` restarted at 1 on every
  replica and every restart, so `POST /v1/admin/ops/alerts/{id}/ack` could acknowledge a **different
  alert than the operator clicked**. One database sequence mints them now.
- The alerts list and the ack state no longer differ per replica, and neither is lost on restart.
- **`StubOperatorActionAuditClient` was the live audit trail in every environment** (it carried
  `matchIfMissing = true` and `GMEPAY_OPERATOR_ACTION_AUDIT_CLIENT` was set nowhere): per-JVM `OA-n`
  ids colliding across replicas, lost on restart, and `recordDurable()` could not fail - so
  "no money-affecting operator action without a durable audit record" was decorative. The stub is now
  opt-in and WARNs at construction; an unrecognised selector leaves no bean so the service refuses to
  boot. **The default did NOT become `rest`**, because `rest` POSTs
  `/v1/audit/operator-actions`, which **no service in this repo exposes** - selecting it today
  fail-closes every audited operator action. Verifying that first is the T1-1 precedent, and it is
  what stopped a straight inversion.

### Changed
- `update(seq, mutator)` runs under `SELECT ... FOR UPDATE`, so a concurrent ack and paging stamp
  cannot drop one another's fields - the read-modify-write that made a Redis hash the wrong *shape*
  for this store. Explicit `TransactionTemplate`s, not `@Transactional`: the boundary is a correctness
  requirement, and an annotation that only works through a proxy would silently do nothing when the
  store is constructed directly.
- `add()` **never throws** (it is called immediately before paging a human, so a storage failure must
  not become a missed page); reads **do** propagate, because "no alerts" from an unreachable store is
  a lie a human acts on. `management.health.db.enabled=false` for the same asymmetry: the datastore
  backs 2 of ~40 surfaces, and marking every replica unready would take the whole console down.
- **The escalation sweep is STILL not ShedLocked**, now that a `LockProvider` exists here. A lock can
  only ever *subtract* escalations, so a stuck lock row would silence the pager; the duplicate it
  would prevent is already prevented at the pager by the shared `PagingCooldown`. A reflection test
  fails the build if anyone adds the annotation, paired with one proving the retention sweep *is*
  locked so neither reads as an oversight.
- The escalation query is capped at `OpsAlertStore.MAX_LIMIT` instead of the old "0 = unlimited",
  which against a table would fetch the whole retention window every tick.
- `docker-compose.yml` (+ `postgres-bff` on host port 5448) and all four Helm values files set
  `SPRING_DATASOURCE_URL` and `GMEPAY_OPERATOR_ACTION_AUDIT_CLIENT=db`.


## 2026-07-30 — Paging dedupe is shared: the BFF no longer pages a human once per replica

### Added
- **`alert/paging/PagingCooldown`** plus `InMemoryPagingCooldown`, `RedisPagingCooldown`
  (`SET NX EX`), `FailoverPagingCooldown` and `PagingCooldownConfig`
  (`gmepay.ops.paging.cooldown-store` = `auto` | `redis` | `memory`; `redis` without a host, or any
  unrecognised value, **refuses to start**).
- `spring-boot-starter-data-redis` — the only shared store this BFF can use, since it owns no
  database. Servlet, not reactive: one `SET NX EX` per page is not work that wants a reactive client.

### Changed
- **`OpsPagingDispatcher` claims the cooldown atomically before paging**, instead of checking a
  per-JVM map and then setting it. A failed delivery — or a throwing paging port — **releases** the
  claim, so the pre-existing rule that only a *delivered* page opens the cooldown survives the change
  to an atomic claim.
- **The old "naturally single-fire across replicas" argument was incomplete.** It was true of the
  consume path (one Kafka consumer per record) and missed the two cases that actually double-page: the
  escalation sweep runs on *every* replica, and a re-fired alert consumed by a *different* replica
  than last time finds an empty cooldown map well inside the 15-minute window.
- **`OpsPagingEscalationScheduler` is deliberately NOT locked to one replica — reversing this class's
  own earlier note** ("single-replica-only; ShedLock it if the BFF gains a DataSource"). The alert
  buffer is per-replica, so a lock would let one replica sweep and leave every *other* replica's
  un-acked CRITICAL alerts never escalated: a missed page, which is worse than the duplicate it
  prevents. The sweep runs everywhere; duplicates are stopped at the pager.
- **This control's failure posture is the deliberate inverse of api-gateway's.** An unreachable Redis
  degrades dedupe to per-JVM and **never suppresses a page** — failing closed would silence the pager
  during an incident, the one moment it exists for. Not configurable, because there is no operational
  position from which "silence the pager when its dedupe cache is unreachable" is the right answer. A
  missed page is not recoverable; a duplicate page is an annoyance.
- `management.health.redis.enabled=false` — a Redis outage must not mark every BFF replica unready
  (taking the admin UI and the partner portal down) over a noise-reduction feature.

### Known limitation, recorded rather than papered over
**`OpsAlertStore` was NOT moved to shared state.** At N>1 the alerts list differs per replica, an ack
recorded on A is invisible on B (so B keeps escalating an acknowledged alert, bounded to one page per
dedupe window), and the control tower's counts are a fraction of the fleet's. Redis is the wrong
*shape* for it: `update(seq, mutator)` is a read-modify-write over a record whose paging stamp and ack
are written by different threads, which on a Redis hash needs `WATCH`/Lua or an ack silently
overwrites a concurrent paging stamp — and once `seq` allocation, capacity eviction and filtered
newest-first queries are added, the thing being described is a **table**: exactly the durable JPA
store already recorded as the follow-up, which would also fix restart durability and give ack an
audit trail. Building the Redis version first means building it twice and shipping the weaker one.
**So run this service at 1 replica** until it has that store, or accept a divergent alerts view
knowingly. It is an operator-surface correctness defect, not a money one, and it constrains no other
service.

### Tests
11 new (487 total, 0 failures). `PagingCooldownAcrossReplicasTest`: two replicas escalating the same
alert page **once** while two per-JVM cooldowns page **twice**; a re-fired alert on another replica is
recorded `SUPPRESSED`; a failed or throwing delivery releases the claim so another replica may retry;
an unavailable Redis still pages **and** still dedupes locally; an absent Redis reply reads as "page
it"; and Redis is never wired without the failover decorator.

## 2026-07-03 — Platform-settings pass-through (feat/platform-settings-be)

Additive. Thin proxy so the admin UI (which only talks to this BFF) can reach config-registry's
generic platform-settings store (owner Goal #3).

### Added
- **Client** `PlatformSettingsClient` (+ `RestPlatformSettingsClient` on
  `gmepay.config-registry.client=rest`, in-memory `StubPlatformSettingsClient` otherwise; stub
  seeds the same five tunables config-registry V039 seeds) and BFF DTO `PlatformSettingView`.
- **Endpoints** `PlatformSettingsController` under `/v1/admin/settings`:
  `GET /`, `GET /{key}`, `PUT /{key}` (body `{value, updatedBy?}`) — each proxies the matching
  config-registry endpoint. Reads gated on `txn.view`, the mutating PUT on `ops:operate`
  (fail-closed via `OpsRbacGuard`, matching neighbouring ops admin surfaces). The authenticated
  principal is forwarded as `updatedBy`.
- **Test** `PlatformSettingsControllerTest` (3, Mockito): the three proxy calls map through with
  the right arguments; principal forwarded as `updatedBy`.

## 2026-07-03 — QR-scheme reconciliation statement (feat/scheme-statement-be)

Additive, read-only, admin-facing. GME produces a per-scheme statement so a QR-scheme partner
(e.g. ZEROPAY, NEPAL) can reconcile the transactions GME recorded against its own records
(owner Goal #6). No new auth surface — gated on `txn.view` (fail-closed) like the other read
endpoints. Edits confined to `services/ops-partner-bff/`.

### Added
- **`GET /v1/admin/schemes/{schemeId}/statement?from=<ISO>&to=<ISO>&page=0&size=50`**
  (`SchemeStatementController`) → `{ schemeId, window:{from,to}, totals:[{currency,count,gross}],
  items:[{txnRef,occurredAt,merchantId,partnerId,amount,currency,status}], page, size, total }`.
  `from`/`to` optional (default last 30 days); `size` capped at 200.
- `SchemeStatement` DTO (money as decimal strings, MONEY_CONVENTION).
- Fetches via the existing `TransactionMgmtClient` over the `gmepay.transaction-mgmt.base-url`
  RestClient. `RestTransactionMgmtClient.list` now forwards the (previously-dropped) `Filter.schemeId`
  as the `schemeId` query param to transaction-mgmt's newly scheme-filterable list endpoint.
- **`totals` sourcing:** transaction-mgmt's `/stats` groups `byCorridor` (= scheme_id) but returns
  only per-corridor *counts* — no currency, no gross. Rather than change that shared aggregate,
  the lighter path for a single-scheme statement is taken: page the scheme's window via
  `GET /v1/transactions?schemeId=&from=&to=` in bounded 200-row chunks and fold per currency
  (bounded — one scheme's window, capped chunk count; never loads unbounded rows).

### Tests
- `SchemeStatementControllerTest` (mocks `TransactionMgmtClient`): items scoped to the scheme +
  newest-first shape; per-currency totals correct over the full window (USD 3/220.50, KRW 1/50000);
  size capped at 200; empty scheme → empty totals/items. 3/3 pass offline.

## 2026-07-03 — live sandbox self-integration keys (feat/sandbox-keys-live)

Additive. The Partner-Portal "Get Started" sandbox API keys are now REAL
credentials issued by auth-identity instead of the in-memory
`StubSandboxKeyClient` (which authorized nothing). The stub remains the
standalone/test default (`matchIfMissing`); no change to the portal response
shape.

### Added
- **`RestSandboxKeyClient implements SandboxKeyClient`**
  (`@Primary @ConditionalOnProperty(name="gmepay.auth-identity.client", havingValue="rest")`).
  - issue → `POST {auth-identity}/internal/auth/keys` with `environment=SANDBOX`,
    `purpose=API`, `pk_test_`/`sk_test_` prefixes; forwards the string path
    partnerId as both numeric `partnerId` and `partnerCode`.
  - list → `GET /internal/auth/keys?partnerId=&environment=SANDBOX`.
  - Maps auth-identity's `environment` → the portal's `scope` (guarded to
    `SANDBOX` if absent). One-time plaintext maps through once; list views are
    secret-free. Uses `${gmepay.auth-identity.base-url:...}` (the same selector
    + base-url as `RestRbacAdminClient`).

### Changed
- `StubSandboxKeyClient` now carries
  `@ConditionalOnProperty(name="gmepay.auth-identity.client", havingValue="stub", matchIfMissing=true)`
  so it and the Rest impl are mutually exclusive (the established Rest/Stub idiom).
  Behaviour unchanged when the selector is unset.

### SANDBOX-scoping (security)
- Every credential is pinned to `environment=SANDBOX` on issue and every list
  query filters `SANDBOX`; keys carry `pk_test_`/`sk_test_` prefixes and land
  under auth-identity's `partner:{code}:SANDBOX` principal — they cannot
  authorize a PRODUCTION / real-money call. The Rest client never sends
  `PRODUCTION` and never touches the 4-eyes / rotation production path.

### Changed (deploy config — rest is now the default there)
- `run-fleet.ps1`: ops-partner-bff gains `--gmepay.auth-identity.client=rest`
  + `--gmepay.auth-identity.base-url=http://localhost:18085`.
- `docker-compose.yml`: ops-partner-bff gains `GMEPAY_AUTH_IDENTITY_CLIENT: rest`
  + `GMEPAY_AUTH_IDENTITY_BASE_URL: http://auth-identity:8080`.
- `deploy/helm/gmepay/values.yaml`: ops-partner-bff env gains
  `GMEPAY_AUTH_IDENTITY_CLIENT: "rest"` + `GMEPAY_AUTH_IDENTITY_BASE_URL`.

### Tests
- `RestSandboxKeyClientTest` (MockRestServiceServer): issue POSTs a
  SANDBOX-scoped request and maps plaintext/prefix/createdAt + environment→scope;
  missing-environment response still reads `SANDBOX`; list forwards the
  `partnerId`+`environment=SANDBOX` filter and returns secret-free views;
  non-numeric partner short-circuits to empty without a network call.
- `SandboxKeyControllerTest` (stub-backed) unchanged and still passes.

## 2026-07-03 — live partner transactions (feat/live-partner-txns)

Additive. Activates the real `RestTransactionMgmtClient` in the real deploys so the Partner
Portal shows a partner's LIVE transactions from transaction-mgmt (empty until real payments
exist). The in-memory `StubTransactionMgmtClient` remains the standalone/test default
(`matchIfMissing`); no change to transaction-mgmt.

### Fixed (security-critical — partner-scoping)
- `RestTransactionMgmtClient.list(Filter)` / `search(SearchQuery)` now **fail closed** when a
  partnerId filter is *supplied but non-numeric*: they return an empty page instead of issuing
  an unscoped (all-partners) query to transaction-mgmt. Previously a non-numeric partnerId was
  silently dropped, which would have returned every partner's transactions — a cross-partner
  leak on the Portal list/recent path. transaction-mgmt filters strictly on the numeric
  `partnerId` column, so live deploys must pass the numeric partner id (JWT/path-derived); only
  a truly absent (null/blank) partnerId means "all partners" (the Admin surface).
- Transaction *detail* scoping was already enforced at the controller layer
  (`PartnerPortalController.transactionDetail` 404s when `summary.partnerId()` != path partnerId,
  covering both unknown and wrong-partner) and is unchanged.

### Changed (deploy config — live is now the default there)
- `run-fleet.ps1`: ops-partner-bff entry gains `--gmepay.transaction-mgmt.client=rest`.
- `docker-compose.yml`: ops-partner-bff service gains `GMEPAY_TRANSACTION_MGMT_CLIENT: rest`.
- `deploy/helm/gmepay/values.yaml`: ops-partner-bff env gains `GMEPAY_TRANSACTION_MGMT_CLIENT: "rest"`.

### Tests
- `RestTransactionMgmtClientTest`: numeric partnerId is forwarded as the `partnerId` query param
  (`list` + `recent`); non-numeric partnerId fails closed with no HTTP call (`list` + `search`).

## 2026-07-03 — journal view proxy (feat/journal-view-be)

Additive, read-only. Reuses the existing `gmepay.revenue-ledger.base-url` RestClient; no new dependency.

### Added
- **`GET /v1/admin/journals?from=&to=&reference=&page=&size=`** → same shape revenue-ledger returns:
  `{ items:[ { journalId, reference, createdAt, lines:[ { account, side, amount, currency } ] } ],
  page, size, total }`. Pure pass-through of revenue-ledger's `GET /v1/journals` so the Admin UI can
  show the DR/CR lines behind every money movement. All params optional (`from`/`to` are ISO-8601
  instants); upstream applies the defaults (last 30 days, size cap 200).
- `RevenueLedgerClient.listJournals(from,to,reference,page,size)` — `RestRevenueLedgerClient` proxies
  the live endpoint (honest empty page on upstream error/unreachable); `StubRevenueLedgerClient`
  returns two deterministic balanced journals so the BFF renders standalone. New `JournalView` /
  `JournalPage` DTOs and `AdminJournalsController`.

## 2026-07-03 — Delivery dashboard overview

Additive. Orchestrates transaction-mgmt delivery stats with the partner list into a
dashboard-shaped overview. Libs + other services untouched.

### Added
- **`GET /v1/admin/delivery/overview?from=&to=`** → `{ window, successRate:{overall,byPartner,
  byCorridor}, declineReasons, activation }`. successRate + declineReasons come from
  transaction-mgmt's `GET /v1/transactions/stats`; `activation` joins each partner's onboarded
  timestamp (config-registry `listPartnerViews().validFrom`) to its earliest approved-transaction
  instant (`GET /v1/transactions/first-approved`), computing `activationHours` and
  `status` = `activated`|`pending`.
- `TransactionMgmtClient.stats(from,to)` + `firstApprovedByPartner()` (additive default methods;
  RestTransactionMgmtClient overrides both against the existing
  `gmepay.transaction-mgmt.base-url` RestClient). New `DeliveryOverview` DTO.
## 2026-07-03 — Self-serve SANDBOX key issuance (Partner Portal "Get Started")

Additive. Lets a logged-in partner mint their own SANDBOX API key from the Partner Portal
Get-Started page — no account-manager / 4-eyes step. The production-key issuance path
(auth-identity `/internal/auth/keys` + the 4-eyes rotation workflow) is untouched.

### Added
- **`POST /v1/portal/{partnerId}/sandbox-keys`** `{name?}` → `201 { keyId, apiKey, prefix,
  scope:"SANDBOX", createdAt }`. `apiKey` is the ONE-TIME plaintext secret — returned exactly
  once; the store keeps only a salted one-way hash (SEC-09 §4). The key is `SANDBOX`-scoped
  (test-prefixed `pk_test_`/`sk_test_`) so it cannot authorize real-money production calls.
- **`GET /v1/portal/{partnerId}/sandbox-keys`** → `[{ keyId, prefix, scope, createdAt }]`
  (never the plaintext) so the portal can list a partner's existing sandbox keys.
- New `SandboxKeyClient` port + default in-memory `StubSandboxKeyClient` reproducing the
  one-time-plaintext / hash-only / SANDBOX-scope contract without booting auth-identity.
  (Prod: forward to auth-identity `POST /internal/auth/keys` with `environment=SANDBOX`.)
- `SandboxKeyControllerTest` pins: 201 + plaintext once, hash-only persistence, SANDBOX scope,
  distinct-secret-per-call, and `toString()` redaction.

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
