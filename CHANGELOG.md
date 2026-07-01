# Changelog

## admin-ui Operations console (branch `feat/ops-console`)

Adds the native React Operations console — the #1 Ops sign-off blocker (backend
existed, no operating screen). Edits scoped to `apps/admin-ui/` only.

### Added
- **"Operations" nav item** (`/operations`, HealthAndSafety icon) near the top of
  the AppShell sidebar — it's the live control surface.
- **`src/app/operations/page.jsx`** — tabbed console:
  - *Control Tower* (default): rollup cards (in-flight, UNCERTAIN/aged, webhook
    backlog, service health, open recon exceptions), float-headroom table with
    at-risk highlighting, operational-status banner (paused/maintenance/suspended
    + reason/since), recent-alerts strip. Auto-refreshes every ~12s + manual
    refresh; `degradedSections` render as "unavailable" rather than crashing.
  - *Kill-switch*: Pause/Resume, Maintenance on/off, Suspend/Unsuspend (entityType
    select + id + reason) — each behind a confirm dialog with success/error toast
    and a status-banner refresh after.
  - *Alerts*: `/v1/admin/ops/alerts` list with severity/type filters.
  - *Transactions & Recovery*: search form → results table → per-row Resolve
    (COMPLETED|REVERSED + reason); webhook replay + recon re-run.
- **`src/api/opsApi.js`** — standalone BFF client (mirrors complianceApi.js).
  Money-affecting ACTION calls send `X-Gme-Permissions: ops:operate` for the
  fail-closed BFF. DEV-only header — in prod it is derived server-side from the
  operator's verified token / PDP (noted in code).
- Vitest coverage for the page (control tower, kill-switch POST, alerts) and an
  AppShell nav assertion.

## harden transaction-mgmt — event emission + ShedLock (branch `fix/transaction-mgmt`)

Fixes defect #1 (money moves with no ledger impact on operator force-resolve) and
adds distributed scheduler locking (#3). Edits scoped to `services/transaction-mgmt/`
only; libs + other services frozen. Additive; new migration only.

### Fixed
- **Operator force-resolve → REVERSED now emits `payment.reversed` (defect #1).** The
  FSM `REVERSED` transition previously emitted only the internal
  `TransactionStatusChanged` event, so an operator reversal of an UNCERTAIN txn
  released the held prefund float and booked no reversing journal — money moved with
  ZERO ledger impact. `TransactionStateMachine` now also appends a
  `PaymentReversedEvent` (new outbox event mirroring the canonical
  `com.gme.pay.contracts.events.PaymentReversedPayload`, topic
  `gmepay.payment.reversed`) from the txn snapshot: `txnRef`, `partnerId`, `schemeId`,
  reversed collection amount + currency, **`reversedUsd`** (the `prefundDeductedUsd`
  held at UNCERTAIN — so prefunding releases exactly what it held), `reason` (the
  operator's resolution reason), `source=OPERATOR`, `occurredAt`. Guarded on non-null
  `partnerId` (same as APPROVED); appended to the durable outbox so it is never
  silently dropped. The FSM status event is still emitted (additive).
- **Operator force-resolve → COMPLETED recognises revenue.** Confirmed + test-locked:
  COMPLETED routes through `stateMachine.transition(..., APPROVED)`, which already
  emits the revenue-bearing `PaymentApprovedEvent` + `TransactionCommittedEvent` a
  normal commit emits — so revenue is recognised on the same signal.

### Added
- **ShedLock distributed scheduler locking (#3).** Added `shedlock-spring` +
  `shedlock-provider-jdbc-template` (5.13.0); V010 `shedlock` table migration
  (engine-neutral PG + H2 PG-mode); `ShedLockConfig` (`@EnableSchedulerLock`, a
  `JdbcTemplateLockProvider` on the existing DataSource with `usingDbTime()`); and
  `@SchedulerLock` on every `@Scheduled` method in the service —
  `ExpirySweeperService.sweep`, `StuckTransactionAlertSweeper.sweep`,
  `OutboxPublisher.publishPending` — so replicas do not double-fire.

### Notes
- Tests (H2 + mocks, no Docker): `ForceResolveEventEmissionTest` (REVERSED emits
  `PaymentReversedPayload` with `reversedUsd`+`reason`; COMPLETED emits the
  revenue-bearing events; idempotent replay emits once); `@SchedulerLock`
  annotation assertion on the sweeper. Full `./gradlew :services:transaction-mgmt:test`
  green (122 tests).

## Wave-3 — config-registry read-contract wiring (branch `w3/config-registry`)

config-registry as the producer of the Wave-3 read contracts. Edits scoped to
`services/config-registry/` only; libs + other services frozen.

### Added
- **partner_scheme location-resolution read** — `GET /v1/schemes/resolve?country=`
  (new `SchemeResolutionController`) returns `List<PartnerSchemeView>` over the
  CURRENT V022 enablements joined to each partner's `operating_country`. Carries
  `direction`, `countryCode`, derived `supportsCpm`/`supportsMpm` (from
  `approval_method_cpm`/`_mpm` presence), `status` (ACTIVE/SUSPENDED from the
  kill switch) and `priority` (null — no column yet). Filterable by country;
  unknown country → empty list. smart-router consumes this.
- **Rule rate-source fields** — V035 adds `rate_coll_source`/`rate_pay_source`
  (roster IDENTITY|LIVE|MANUAL|PARTNER, DEFAULT 'LIVE') to `partner_rule`; the
  GET `/v1/partners/{id}/rules` `RuleView` now emits them (NULL→LIVE on read).
  rate-fx consumes this.
- **Credit-limit push to prefunding** (IR-pf-2) — gated REST client
  (`PrefundingCreditLimitClient`: `RestPrefundingCreditLimitClient` @
  `gmepay.prefunding.client=rest`, `NoOpPrefundingCreditLimitClient` default).
  `CreditLimitPusher` merges `credit_limit_usd` (V015) + daily/monthly/annual +
  daily-txn-count caps (V020/V034) and PUTs `/internal/v1/prefunding/{partnerId}/credit-limit`
  on every prefunding-config or limits write. MockRestServiceServer-tested.
- **Onboarding → KYB verify** — gated `KybVerifyClient`
  (`RestKybVerifyClient` calls kyb-adapter `POST /v1/kyb/verify`,
  `StubKybVerifyClient` default). `KybService.runVerification(...)` +
  `POST /v1/partners/{id}/kyb/verify` persist provider ref + collapsed decision
  (V036 `verification_decision`/`_reason` columns) on a fresh SCD-6 row.
  MockRestServiceServer-tested.

### Notes
- Migrations V035/V036 are ADR-013 Expand-phase additive ALTERs (nullable /
  DEFAULTed), engine-neutral (PG + H2 PG-mode).
- Credit-limit push fires on cap-SET; activation-time push not yet wired.
