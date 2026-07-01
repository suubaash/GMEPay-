# payment-executor — CHANGELOG

All notable changes to the payment-executor service. Newest first.

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
