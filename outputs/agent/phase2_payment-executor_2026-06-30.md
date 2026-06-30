> 작업: Phase2 payment-executor wiring / 출처: agent

# Phase 2 — payment-executor cross-service wiring

**Build:** `./gradlew :services:payment-executor:test` = **BUILD SUCCESSFUL**, 100 tests, 0 failures / 0 skipped.
Scope: edits confined to `services/payment-executor/` only; libs + other services untouched.

## What was wired

1. **payment.approved (PRODUCER).** `PaymentEvents.PaymentApproved` now carries the revenue-bearing
   fields (collectionMarginUsd, payoutMarginUsd, serviceChargeAmount/Ccy, feeSharePct, partnerId,
   schemeId, txnRef, revenueDate) snapshotted on the authorization at authorize, and maps to the
   canonical `com.gme.pay.contracts.events.PaymentApprovedPayload` via `payload()`. Published through
   the existing `EventPublisher` seam (LogEventPublisher behind `@ConditionalOnMissingBean`). This is
   what revenue-ledger + notification-webhook consume.

2. **Canonical ErrorCode flip.** `GET /v1/payments/{id}` → `ErrorCode.PAYMENT_NOT_FOUND` (404);
   `GET /v1/balance` LOCAL → `ErrorCode.FORBIDDEN` (403). String-literal workarounds removed; wire
   `code` values unchanged.

3. **Prefunding REST binding (CONSUMER).** `RestPrefundingClient` gains `deductionHistory` (GET
   `/v1/prefunding/{code}/deductions` → `PrefundingDeductionHistoryView`), `reserveCpm` (POST
   `.../reservations`, `PrefundingReserveRequest`/`Response`, 402→InsufficientPrefunding), `releaseCpm`
   (DELETE `.../reservations`, `PrefundingReleaseRequest`). `GET /v1/balance?include_history=true`
   appends `recent_deductions` (non-fatal). All interface methods `default`-throw → fakes stay valid.
   Tested with MockRestServiceServer (prefunding not running).

4. **Strict fake-merchant hardening.** `GmeremitPaymentService.pay()` now HARD-FAILS on merchant
   lookup miss/unreachable with `MerchantNotFoundException` (→404). Synth of UNKNOWN merchant gated on
   explicit dev flag `gmepay.payment.dev-synth-merchant` (default false); legacy `merchant-validation`
   no longer enables synth.

## Remaining mismatch / open

- `schemeId` on the event rides as 0 (orchestrator carries scheme CODE, not config-registry's numeric
  id) — matches the per-txn revenue capture; numeric scheme-id mapping is a follow-on.
- `reserveCpm`/`releaseCpm` are bound + unit-tested but NOT yet called from `executeCpm` (CPM still uses
  the txnRef-keyed `reserve`/`capture`/`release`); wiring into the CPM orchestrator path is the next step.
- prefunding's actual reservation endpoint paths (`/reservations` POST/DELETE) assumed from the contract
  — confirm against prefunding's real routes at integration.
