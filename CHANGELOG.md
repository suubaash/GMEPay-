# Changelog

## Platform-wide defaults: graceful shutdown + log correlation (branch `feat/platform-defaults`)

Maturity hardening delivered once in the shared `libs/lib-errors` module so all 18 GMEPay+ services
inherit two behaviours with NO per-service edit: (1) graceful shutdown / connection draining for
zero-downtime deploys, and (2) the iteration-4 correlation id surfaced in the log pattern. Purely
additive — no change to error/RBAC/internal-auth/correlation/trace behaviour. No new external
dependency (Spring Boot's own `EnvironmentPostProcessor` SPI only), so the build resolves fully
offline from the Gradle cache.

Implemented as an `EnvironmentPostProcessor` (not an auto-config) so the values are contributed as
DEFAULTS before the context builds. They are added via `addLast(...)` = LOWEST precedence, so a
service's own `application.yml`, env vars, and CLI args all override them — near-zero blast radius.

### Added
- **`com.gme.pay.platform.PlatformDefaultsEnvironmentPostProcessor`**: adds a single
  `MapPropertySource` named `gmepay-platform-defaults` at lowest precedence with defaults
  `server.shutdown=graceful`, `spring.lifecycle.timeout-per-shutdown-phase=25s`, and
  `logging.pattern.level=%5p [%X{correlationId:-}]` (exact iteration-4 MDC key `correlationId`; `:-`
  renders empty on non-request threads). Escape hatch: `gmepay.platform-defaults.enabled=false`
  short-circuits the contribution. Registered via a new `META-INF/spring.factories` under
  `org.springframework.boot.env.EnvironmentPostProcessor`.

## End-to-end correlation-ID propagation (branch `feat/correlation-id`)

Maturity hardening: one payment can now be traced across every service by a single id present in
both logs (SLF4J MDC) and HTTP responses. Implemented once in the shared `libs/lib-errors`
auto-config, so all 18 dependent services get it automatically. Purely additive — no change to
error semantics, RBAC, internal-auth, or the trace tap's behaviour. No new external dependency
(servlet `Filter` + SLF4J `MDC` + a `ClientHttpRequestInterceptor` only), so the build resolves
fully offline from the Gradle cache.

A NEW id was added rather than reusing the trace tap's header: the trace tap's
`X-Gme-Trace-Caller` carries the *caller's identity* for the trace-console graph and propagates no
request/correlation id, so there was nothing to reuse. The two are complementary and independent.

### Added
- **`com.gme.pay.correlation.CorrelationIdFilter`** (servlet `OncePerRequestFilter`): reads the
  inbound `X-Correlation-Id` (also accepts the `X-Request-Id` alias); if absent/blank, generates a
  UUID. Puts it in SLF4J MDC under key `correlationId`, echoes it back on the `X-Correlation-Id`
  response header, and ALWAYS clears the MDC key in a `finally` (no leakage across pooled request
  threads).
- **`com.gme.pay.correlation.CorrelationIdClientHttpInterceptor`** (`ClientHttpRequestInterceptor`):
  when the `correlationId` is present in MDC, stamps `X-Correlation-Id` on outbound requests so the
  downstream service's filter reuses the SAME id → one id spans the whole call chain. No-op when MDC
  is empty (schedulers/async) or the header is already set.
- **`com.gme.pay.correlation.CorrelationIdAutoConfiguration`** (`@AutoConfiguration`, appended to
  `AutoConfiguration.imports`): registers the filter via a `FilterRegistrationBean` at
  `Ordered.HIGHEST_PRECEDENCE` (BEFORE `InternalAuthFilter` at +10 and the RBAC context filter at
  +20, so even auth-rejection logs carry the id); exposes the interceptor as a plain bean; and
  registers `RestClientCustomizer` / `RestTemplateCustomizer` that apply it to the Spring-Boot
  builders. Gated by `gmepay.correlation.enabled` (defaults TRUE, `matchIfMissing`).
- **`com.gme.pay.correlation.CorrelationHeaders`** — shared header/MDC key constants.

### Opt-in for services that build their own `RestClient`
Services that construct a `RestClient` directly (not via the shared `RestClient.Builder`) add the
id to outbound calls with one line:
`.requestInterceptor(correlationIdClientHttpInterceptor)` (inject the auto-configured bean).
Rewiring every service's client is a deliberate follow-up; this iteration ships the filter, the
reusable interceptor, and auto-registration on the shared builders.

### Log visibility
The MDC value is present regardless of log config. Setting `logging.pattern.level` from the
auto-config was deliberately NOT done — injecting default properties fleet-wide is fragile (each
service's `application.yml` can silently override it, and it needs a heavier `EnvironmentPostProcessor`).
Instead, services surface the id by adopting this property (recommended for `application.yml`):
`logging.pattern.level=%5p [%X{correlationId}]`.

## Actuator health/readiness/liveness + metrics fleet-wide (branch `feat/actuator-health`)

Maturity hardening: every Spring Boot core service now exposes proper probes and a metrics
surface, not just api-gateway + auth-identity. Purely additive — no business logic, controller,
or money-path change. No new external dependency: uses only the already-cached
`spring-boot-starter-actuator` (no `micrometer-registry-prometheus`), so the build resolves fully
offline from the Gradle cache.

### Added
- **`spring-boot-starter-actuator`** added to the 15 boot services that lacked it: transaction-mgmt,
  prefunding, revenue-ledger, config-registry, rate-fx, settlement-reconciliation, merchant-qr-data,
  ops-partner-bff, scheme-adapter-zeropay, scheme-adapter-nepal, smart-router, qr-service,
  notification-webhook, reporting-compliance, payment-executor. Declared per-service (the root
  `subprojects {}` block also covers libs/e2e/simulators, so a shared declaration would pollute
  non-boot modules).
- **Consistent management config** across the whole fleet (incl. api-gateway + auth-identity):
  `management.endpoints.web.exposure.include=health,info,metrics`,
  `management.endpoint.health.probes.enabled=true` (enables `/actuator/health/liveness` +
  `/actuator/health/readiness`), `management.endpoint.health.show-details=never` (no auth wiring
  assumed in the sandbox; no internals leak). env/beans/heapdump/shutdown stay off. Management
  runs on the app port (no separate port) so fleet/compose probes hit the app port.

### Changed
- **scheme-adapter-nepal** — removed its separate management port (`management.server.port=8093`);
  probes now serve on the app port (8092), matching the fleet convention.
- **notification-webhook** / **api-gateway** — normalized their pre-existing management config to the
  fleet standard (added `metrics`/`info`, `probes.enabled`, `show-details=never`; api-gateway's
  `prometheus` exposure dropped since no registry artifact is on the classpath).

## Self-serve developer onboarding (branch `feat/selfserve-onboard`)

Gives partners a self-serve front door so integration no longer needs one engineer per
partner. Additive across the Partner Portal + the Ops/Partner BFF only.

### Added
- **Partner Portal "Get Started" page** (`apps/partner-portal-ui/src/app/get-started`) — a new
  first nav item where a logged-in partner (1) generates a SANDBOX API key (shown ONCE, with a
  "copy it now" warning; existing keys listed by prefix), (2) copies a curl quickstart for
  `POST /v1/pay/classify` and `POST /v1/pay` pre-filled with the new key + a configurable
  `NEXT_PUBLIC_SANDBOX_API_BASE`, and (3) reads a compact endpoint reference.
- **`POST/GET /v1/portal/{partnerId}/sandbox-keys`** on ops-partner-bff — self-serve SANDBOX
  key issuance. Plaintext returned once; store keeps only a hash; `scope="SANDBOX"` so the key
  cannot authorize production calls. The production 4-eyes key flow is untouched. See the
  ops-partner-bff CHANGELOG for the wire detail.

## GMEPay+-authoritative QR classification (branch `feat/hub-qr-classify`)

Makes GMEPay+ — not the wallet — the authority for what a scanned QR *is*. Before,
the GMERemit wallet hardcoded `network=FONEPAY, currency=NPR` locally and asked the
partner sim directly; the hub was never consulted for the corridor/currency.

### Added
- **`POST /v1/pay/classify`** on payment-executor — given a raw QR payload, returns
  `{supported, network, country, currency, mode, scheme}` using the same
  `QrSchemeClassifier` + smart-router routing the pay path uses (Fonepay → NP / NPR /
  NEPAL; ZeroPay → KR / KRW). No payment executed.
- `FailoverPaymentRouter.classifyQr(...)` + `QrClassification` record (currency
  derived from the resolved scheme, so it reflects the actual route incl. failover).

### Changed
- **GMERemit wallet** now calls `/v1/pay/classify` and displays the currency/network/
  country/scheme **GMEPay+ returns** (`detectedBy=GMEPAY+`), falling back to Fonepay/NPR
  labels only if the hub is unreachable. Merchant name/city/amount still come from the
  partner decode. UI is unchanged (it keys off `currency==='NPR'`).

### Tests
- `classifyQr` → Fonepay resolves to NP/NPR/NEPAL; no-route → unsupported, null currency.
- Wallet scan test now stubs `hub.classify` and asserts the hub-driven fields. All green.

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
