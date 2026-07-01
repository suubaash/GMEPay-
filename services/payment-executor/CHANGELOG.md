# payment-executor — CHANGELOG

All notable changes to the payment-executor service. Newest first.

## [fix/payment-executor] — 2026-07-02 (harden kill-switch: fail-CLOSED for security + DECLINE_SPIKE)

### Fixed
- **Defect #4 — kill-switch safety inversion (fail-OPEN → fail-CLOSED for security).** The operational
  gate is a kill switch, but `RestOperationalStatusClient` defaulted to fail-**OPEN** on an unreachable
  config-registry with no client timeout: a cold executor (no cached value) during a config-registry
  outage would let a **suspended/paused partner transact**, defeating the switch. Fix: the default is now
  fail-**CLOSED for the security flags** (`systemPaused` / `maintenanceMode` / partner/scheme/route
  *suspended*) — when status is unreachable/unknown and there is no fresh or last-known-good cached value,
  the client returns a synthetic `systemPaused` status so the gate DENIES the new authorization with the
  existing `SYSTEM_PAUSED` code. A last-known-good cached value is still preferred over either policy, so a
  brief blip does not flip behaviour; only a genuinely no-signal cold executor fails closed. Config default
  flipped: `gmepay.ops.status.fail-open` now defaults to **`false`** (set `true` to restore legacy
  allow-on-outage). Additive; no contract/lib change.
- **Defect #4 — hard client timeout.** The `RestOperationalStatusClient` RestClient now sets explicit
  connect + read timeouts (`gmepay.ops.status.connect-timeout-millis` /
  `read-timeout-millis`, default **500ms** each) via `ClientHttpRequestFactorySettings`, so a HUNG
  config-registry can no longer stall the pay path. A timeout surfaces as `ResourceAccessException` →
  treated as unreachable → the fail-closed-security rule applies.

### Added
- **Defect #5 — `DECLINE_SPIKE` ops alert** (`alert/DeclineSpikeMonitor` + local `alert/OpsAlertEvent`).
  A lightweight in-memory rolling-window decline counter per partner AND per classified scheme/network.
  When the decline rate over `window-seconds` (default 60s) strictly exceeds `threshold-rate`
  (default 0.5) with at least `min-samples` (default 20) outcomes in-window, it emits an
  `OpsAlertPayload` (`alertType=DECLINE_SPIKE`, severity WARN / CRITICAL≥0.8, `subjectRef`=partner/scheme)
  onto `gmepay.ops.alert` via the `EventPublisher` seam (`LogEventPublisher` fallback logs it). Per-subject
  cooldown (`cooldown-seconds`, default 300s) suppresses repeats. **Default OFF**
  (`gmepay.decline-spike.enabled=true` to enable); injected `@Nullable` into `WalletPayController` so it is
  purely additive. Alerting never throws into the pay path. A `Clock` bean (`@ConditionalOnMissingBean`,
  system-UTC) was added for the monitor.

### Tests
- `RestOperationalStatusClientTest`: default fail-CLOSED on unreachable+no-cache; last-known-good cache
  preferred over the fail-closed default on a later outage; a real hung local HTTP server proves the read
  timeout fires within budget and is treated as unreachable → fail-closed.
- `OperationalGateTest`: the synthetic unreachable (`systemPaused`) status → `SYSTEM_PAUSED` denial.
- `DeclineSpikeMonitorTest`: a decline burst emits `DECLINE_SPIKE`; below-min-samples and all-approved do
  not alert; cooldown suppresses the repeat.

## [ops/payment-executor] — 2026-07-01 (Operations operational gate)

### Added
- **`OperationalGate`** (`domain/OperationalGate`) — checked at the START of every NEW payment
  authorization to refuse new work while the platform is globally paused / in maintenance, or when
  the partner / scheme / route resolved for THIS payment is individually suspended. Precedence:
  `systemPaused` → `maintenanceMode` → partner → scheme → route; the first match throws
  `OperationalGateException` with a stable canonical code. Case-insensitive, trimmed list matching;
  `null` references skip their per-entity check (partial resolution at gate time).
- **`OperationalStatusClient`** + `RestOperationalStatusClient` — reads config-registry's
  `GET /v1/ops/operational-status` → `OperationalStatusView` (lib-api-contracts). Gated
  `@ConditionalOnProperty(gmepay.config-registry.base-url)`, with a short in-memory cache
  (`gmepay.ops.status.cache-ttl-millis`, default 3000ms) so the hot pay path does not round-trip
  per payment. `FixtureOperationalStatusClient` (`@ConditionalOnMissingBean`) returns
  `OperationalStatusView.allClear()` so tests / a no-config-registry sandbox proceed.
- **Fail-open vs fail-closed** — `gmepay.ops.status.fail-open` (default **true** = fail-OPEN → allow
  when status unreachable and no cached value). A last-known-good cached value is preferred over
  either policy so a brief config-registry blip does not flip behaviour.
- **Gate hooks** — wallet `POST /v1/pay` (covers the GMEREMIT/SENDMN inbound branches AND the
  FailoverPaymentRouter outbound branch; gated by partner alias + classified network as route) and
  the orchestrated `POST /v1/payments/authorize` (gated by partner code + scheme id + direction,
  AFTER the idempotent-replay check so an in-flight replay is never gated). Confirm/capture, refund,
  cancel and status lookups are NOT gated — in-flight payments complete even mid-pause.
- **Error surfacing** — `OperationalGateException` → HTTP 503 (retryable) via `PaymentExceptionHandler`,
  emitted with the stable codes `SYSTEM_PAUSED` / `PARTNER_SUSPENDED` / `SCHEME_SUSPENDED` /
  `ROUTE_SUSPENDED` through the `ApiError(code, …)` string ctor (lib-errors is frozen).

### Integration request
- **lib-errors** does not yet carry `SYSTEM_PAUSED` / `PARTNER_SUSPENDED` / `SCHEME_SUSPENDED` /
  `ROUTE_SUSPENDED` as `ErrorCode` enum members. They are emitted as literal codes via the
  `ApiError(String code, …)` ctor for now; promote to `ErrorCode` (503, retryable) when lib-errors
  next unfreezes so the codes are centrally documented.

## [fo/payment-executor] — 2026-07-01 (ADR-016 QR-classified failover routing)

### Added
- **`QrSchemeClassifier`** — parses a scanned QR into `{networkIdentifier, country, mode}`
  (ADR-016 §1). EMVCo: reads Merchant Account Information templates (tags 26–51), sub-tag `00`
  = network GUID/AID (`com.zeropay`, `fonepay.com`, NepalPay GUID…); country from tag `58`;
  mode MPM. JSON QRs (Khalti/mobank) classified by shape. A substring-marker fallback keeps
  well-known networks routable from a slightly non-conformant QR. The QR's network identifier
  is the deterministic routing key, not the country.
- **`SmartRouterClient`** — resolves `(network, country, mode, direction)` → ordered
  `List<PartnerSchemeView>` candidates (priority order). `RestSmartRouterClient`
  (`GET {smart-router}/v1/route/resolve`) gated `@ConditionalOnProperty(gmepay.smart-router.base-url)`;
  in-process `FixtureSmartRouterClient` fallback (`@ConditionalOnMissingBean`) resolves well-known
  networks so tests / a no-router sandbox route deterministically (single candidate = pre-ADR-016
  behaviour). Candidate `schemeId` maps straight to the `SchemeClientRouter` scheme code.
- **`SchemeClient.lookupStatus(schemeId, reference)`** → `APPROVED|PENDING|REJECTED|NOT_FOUND` —
  the anti-double-charge guard (ADR-016 §4). Implemented in `NepalRestSchemeClient`
  (`GET /internal/scheme/nepal/status?reference=`) and `RestSchemeClient`
  (`GET /internal/scheme/zeropay/status?reference=`); `SchemeClientRouter` routes it by scheme code.
  Default / unknown / unreachable → best-effort `NOT_FOUND`.
- **`FailoverPaymentRouter`** — the ADR-016 §3–4 engine on the MPM scan `/v1/pay` path: classify QR
  → resolve ordered candidates → walk them (bounded by `gmepay.routing.max-hops`, default 3),
  `submitMpm(schemeId=candidate.schemeId, …)`. APPROVED → done. **Business decline**
  (`invalid_qr / unsupported_qr / receiver_not_found / receiver_not_eligible / insufficient /
  duplicate_reference`) → **TERMINAL, no failover**. **Technical failure** (timeout / 5xx /
  SCHEME_UNAVAILABLE / connect) → `lookupStatus(candidate, reference)`; APPROVED/PENDING → return
  that (NO double-charge, no second submit); else fail over. All exhausted → SCHEME_UNAVAILABLE.
  Each attempt (partner / outcome / reason) recorded in the attempt trail (resilient). Business-
  decline vs technical is distinguished from the canonical `ErrorCode`/exception the adapters already
  return (`SchemeDeclinedException` w/ business code = terminal; `PaymentException`/`SchemeTimeout`/
  non-business decline code = technical).
- **`WalletPayController`** — the retired `NepalQrDetector` branch is replaced by the
  `FailoverPaymentRouter` for scanned-QR MPM payments to a **known non-ZeroPay network** (subsumes
  Nepal: a Fonepay QR classifies to `fonepay.com` → Nepal candidate). ZeroPay QRs
  (`com.zeropay` / `5802KR`) and the SENDMN path keep the unchanged GMEREMIT/SENDMN services, so
  their merchant validation + fee behaviour is preserved exactly. `FailoverPaymentRouter` added to
  the primary ctor; the 2-arg test ctor still compiles (defaults it null).

### Removed
- **`NepalQrDetector`** (+ its test) — retired per ADR-016. Its Nepal string-match cases are
  subsumed by `QrSchemeClassifier` (Nepal GUIDs → `fonepay.com`/`nepalpay`/`khalti`).
  (`NepalPaymentService` remains as a standalone bean but is no longer on the `/v1/pay` path —
  its Nepal dispatch is now the failover router's single Nepal candidate.)

### Tests
- `QrSchemeClassifierTest` (fonepay.com / com.zeropay / khalti-JSON / country tag / unknown).
- `FailoverPaymentRouterTest`: (a) primary technical-fail + lookup NOT_FOUND → secondary APPROVED;
  (b) primary business-decline → terminal, secondary NOT tried; (c) primary timeout + lookup
  APPROVED → returns primary, **no second submit (no double-charge)**; (d) single-candidate ZeroPay
  → unchanged APPROVED; plus no-candidates and all-exhausted.
- `lookupStatus` cases added to `NepalRestSchemeClientTest` / `RestSchemeClientTest`
  (`MockRestServiceServer`). `WalletPayControllerTest` updated: Fonepay QR → failover router;
  ZeroPay QR still → GMEREMIT. Full suite green (128 tests, 0 failures).

## [fix/wallet-nepal-routing] — 2026-07-01 (wallet /v1/pay Nepal QR routing)

### Fixed
- **Nepal Fonepay QR paid via the wallet returned DECLINED / HUB_ERROR.** `WalletPayController`
  dispatched `POST /v1/pay` ONLY by `partner`, so a Fonepay QR (which arrives as
  `partner=GMEREMIT`) went down the ZeroPay domestic path — ZeroPay merchant-lookup 404'd /
  couldn't parse it. Nepal is now decided by the QR content, not the partner.

### Added
- **`NepalQrDetector`** — `isNepal(qrPayload)` flags a Nepal QR from any marker: `fonepay.com`,
  EMVCo country tag `5802NP`, or `khalti`/`nepalpay`/`npqr` (case-insensitive). Does not misfire
  on ZeroPay QRs (`com.zeropay` / `5802KR`). Unit-tested with the sample Fonepay + ZeroPay QRs.
- **`NepalPaymentService.pay(qrPayload, amount, userRef)`** — mirrors `GmeremitPaymentService`'s
  shape/`WalletResult` but builds an `MpmSubmitRequest` with `schemeId="NEPAL"` and submits via
  the injected `SchemeClient` (the `@Primary SchemeClientRouter`) → Nepal adapter. Skips the
  ZeroPay `merchant-qr-data` validation (the adapter resolves the merchant). Treats the wallet
  amount as NPR for now (adapter → paisa ×100 HALF_UP); TODO(fx) KRW→NPR via rate-fx is a
  follow-up. Records the txn in transaction-mgmt (resilient); adapter decline/failure → DECLINED
  with the adapter's reason (not a generic HUB_ERROR).
- **`WalletPayController`** — routes to `NepalPaymentService` when `NepalQrDetector.isNepal(...)`,
  BEFORE the partner branch; GMEREMIT/SENDMN branches unchanged. `NepalPaymentService` added to
  the primary ctor; the 2-arg test ctor still compiles (defaults it to null).
- Tests: `NepalQrDetectorTest`; two `WalletPayControllerTest` cases (Fonepay QR → Nepal APPROVED;
  ZeroPay QR still → GMEREMIT). Full suite green.

## [na/wiring] — 2026-07-01 (NEPAL scheme-keyed adapter dispatch)

### Added
- **`SchemeClientRouter`** (now the `@Primary` `SchemeClient`): scheme-keyed dispatch.
  Reads the scheme code off each request (`submitMpm`/`submitCpm`) or the `schemeId` arg
  (`checkBalance`) and delegates — `NEPAL` → `NepalRestSchemeClient`, everything else /
  unknown / null → the ZeroPay `RestSchemeClient` (default). `cancelPayment` carries no
  scheme code and is a ZeroPay two-phase concept, so it routes to the default.
- **`NepalRestSchemeClient`**: single-phase adapter for `scheme-adapter-nepal`. Both
  MPM and CPM submit land on `POST /internal/scheme/nepal/submit` (submit = authorize+
  commit); the `{schemeTxnRef,status,amountPaisa}` response maps into the existing
  `MpmSubmitResponse`/`CpmSubmitResponse` shape (schemeApprovalCode ← status, approvedAt
  ← now). Base-url config key `gmepay.scheme-adapters.NEPAL.base-url` (default
  `http://localhost:18091`). `cancelPayment` throws (Nepal has no cancel endpoint).
- **`SchemeId`**: `NEPAL` appended as id `8` (existing 1..7 ids kept stable).
- Tests: `NepalRestSchemeClientTest` + `SchemeClientRouterTest` (`MockRestServiceServer`).

### Changed
- `RestSchemeClient` is no longer `@Primary` (the router is); its ZeroPay behaviour and
  base-url default (`gmepay.scheme-adapter-zeropay.base-url`) are unchanged.

## [w3/payment-executor] — 2026-06-30 (Wave-3 cross-service reconcile)

### Fixed
- **Prefunding CPM reserve/release URL.** `RestPrefundingClient.reserveCpm` now POSTs to prefunding's
  real `POST /internal/v1/prefunding/{partner}/reserve` and `releaseCpm` POSTs to
  `POST /internal/v1/prefunding/{partner}/release` (was the non-existent `/reservations` POST/DELETE).
  Shared `PrefundingReserveRequest`/`Response`/`ReleaseRequest` DTOs unchanged. Tests updated.
- **CPM execution now invokes reserveCpm/releaseCpm.** `PaymentOrchestrator.executeCpm` OVERSEAS path
  RESERVES via the canonical idempotent `reserveCpm` (idempotencyKey == txnRef) at authorize and
  RELEASES via `releaseCpm` on scheme decline, replacing the txnRef-keyed `reserve`/`release` on the
  CPM path (capture-on-success still uses the txnRef `capture` — no captureCpm exists). They were
  bound + unit-tested but never called.
- **schemeId code→numeric.** New `SchemeId.resolve(code)` maps the carried scheme CODE (e.g.
  `zeropay`) to a stable 1-based numeric id off config-registry's canonical scheme roster
  (ZEROPAY=1…QRIS=7; config-registry's catalog is code-keyed with no numeric surrogate / endpoint).
  The `payment.approved` event (`PaymentController.publishApproved`) and the per-txn revenue
  capture + commission split (`confirmMpm`) now carry the resolved id instead of 0.
- **Margins on commit/create (FX1015 zero-margin).** `TransactionClient.CreateRequest` +
  `StatusPatch` extended (additive, back-compat ctors) with the rate-lock pool fields; `authorizeMpm`
  populates them on create from the locked quote (offerRateColl, crossRate, collectionUsd,
  payoutUsdCost, collection/payout margins) and `confirmMpm` carries margins + collectionUsd on the
  APPROVED commit, so transaction-mgmt persists real margins. Cost rates (costRateColl/Pay) are not
  on the quote view / authorization snapshot → sent null.

### Tests
- `RestPrefundingClientTest`: reserveCpm/releaseCpm now expect the `/internal/v1/.../reserve|release` URLs.
- `PaymentOrchestratorCpmTest`: CPM fakes assert reserveCpm/releaseCpm (txnRef reserve/release now throw).
- `SchemeIdTest`: roster mapping, suffix tolerance, UNSET fallback.

## [p2/payment-executor] — 2026-06-30 (Phase 2 cross-service wiring)

### Added
- **Canonical `payment.approved` event payload.** `PaymentEvents.PaymentApproved` now carries the
  revenue-bearing fields (collectionMarginUsd, payoutMarginUsd, serviceChargeAmount/Ccy, feeSharePct,
  partnerId, schemeId, txnRef, revenueDate) snapshotted at authorize, and maps to the canonical
  lib-api-contracts `PaymentApprovedPayload` (camelCase, money as decimal strings) via `payload()` —
  the shape revenue-ledger + notification-webhook consume. Still rides the existing `EventPublisher`
  seam (LogEventPublisher behind `@ConditionalOnMissingBean`; outbox→Kafka supersedes at integration).
  `schemeId` rides as 0 (orchestrator carries scheme CODE, mirroring the per-txn revenue capture).
- **Prefunding REST binding (IR-pe-2 + CPM reserve/release).** `PrefundingClient` +
  `RestPrefundingClient` gain `deductionHistory(code, limit)` (`GET /v1/prefunding/{code}/deductions`
  → `PrefundingDeductionHistoryView`), `reserveCpm(...)` (`POST .../reservations` with
  `PrefundingReserveRequest`/`Response`), and `releaseCpm(...)` (`DELETE .../reservations` with
  `PrefundingReleaseRequest`). All new methods are `default`-throw on the interface so hand-written
  fakes stay valid. `GET /v1/balance?include_history=true[&limit=N]` now appends `recent_deductions`
  (non-fatal: a history hiccup degrades to balance-only). Tested via `MockRestServiceServer`.
- New `MerchantNotFoundException` → canonical `ErrorCode.MERCHANT_NOT_FOUND` (404) handler.

### Changed
- **Canonical error codes (Phase 2 flip).** `GET /v1/payments/{id}` 404 now uses
  `ErrorCode.PAYMENT_NOT_FOUND` and `GET /v1/balance` LOCAL 403 uses `ErrorCode.FORBIDDEN`, replacing
  the String-literal `ApiError` workarounds. Wire `code` values unchanged.
- **Hardened the lenient fake-merchant bypass (`GmeremitPaymentService.pay`).** STRICT is now the only
  non-dev behavior: a merchant-qr-data miss/unreachable HARD-FAILS with `MerchantNotFoundException`
  instead of synthesizing an UNKNOWN merchant. Synth is gated behind the explicit dev flag
  `gmepay.payment.dev-synth-merchant` (default false); the legacy `merchant-validation=lenient` setting
  alone no longer enables it. (Legacy `merchant-validation` is still read by `SendmnPaymentService`.)
- `services/payment-executor/build.gradle`: added `implementation project(':libs:lib-api-contracts')`
  for the cross-service contract DTOs.

### Tests
- `PaymentControllerIdempotencyTest`: asserts the canonical `PaymentApprovedPayload` revenue fields.
- `RestPrefundingClientTest`: deductionHistory bind, reserveCpm bind + 402→InsufficientPrefunding,
  releaseCpm DELETE.
- `BalanceControllerTest`: `?include_history` appends `recent_deductions`; omitted otherwise.
- New `GmeremitPaymentServiceMerchantStrictTest`: strict hard-fail (no scheme call) vs dev-synth proceed.

## [agent/payment-executor] — 2026-06-30

### Added
- **5.2-T27 — GET /v1/balance prefunding balance inquiry.** New `BalanceController`
  delegating to the prefunding service via a new `PrefundingClient.balance(partnerCode)`
  seam (implemented in `RestPrefundingClient` against
  `GET /v1/prefunding/{code}/balance`; default-throws so existing fakes stay valid) —
  payment-executor owns no prefunding store. LOCAL partners get HTTP 403 `FORBIDDEN`;
  OVERSEAS get balance + `is_below_threshold`. Money serialized as decimal strings per
  MONEY_CONVENTION. Covered by `BalanceControllerTest` + `RestPrefundingClientTest`.
- **5.2-T13 / 5.6-T11 — payment lifecycle event emission.** The service now EXPOSES
  its contract events through a `lib-events` `EventPublisher` seam: `payment.approved`
  (on confirm capture+APPROVE), `payment.failed` (on scheme DECLINE at confirm), and
  `payment.cancelled` (on a successful same-day cancel). New `PaymentEvents` domain
  records (aggregateId = payment_id, money fields alongside) and an
  `EventPublisherConfig` wiring a no-infra `LogEventPublisher`
  (`@ConditionalOnMissingBean` so an outbox→Kafka publisher can supersede it at
  integration with no caller change). `PaymentControllerIdempotencyTest` now asserts
  exactly one `payment.approved` on a won claim and none on a lost claim.

- **5.2-T16 — GET /v1/payments/{id} status retrieval.** Owner-scoped lookup
  (`PaymentAuthorizationRepository.findByPaymentIdAndPartnerId`) returning a
  `PaymentDetailResponse`. A payment owned by another partner (or absent) returns
  HTTP 404 `PAYMENT_NOT_FOUND` — never 403 — so ownership is not leaked. Entity
  status is mapped to the lowercase API contract (CONFIRMED→approved, FAILED→failed,
  UNCERTAIN→uncertain, RELEASED/EXPIRED→cancelled, else pending);
  `prefund_deducted_usd` is emitted only for OVERSEAS+CONFIRMED. New
  `PaymentNotFoundException` mapped in `PaymentExceptionHandler`. Covered by
  `GetPaymentControllerTest`.
