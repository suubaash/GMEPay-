> 작업: Phase2 shared contracts / 출처: agent

# Phase 2 Step 1 — Shared contract definitions (additive)

Branch: `integration/fleet-2026-06-30`. All changes purely additive (new files + new enum constants). Full `compileJava compileTestJava --parallel` = **BUILD SUCCESSFUL** (14 pre-existing deprecation warnings, none from these changes).

## 1. lib-errors — `ErrorCode` enum (6 added, before INTERNAL_ERROR)
File: `libs/lib-errors/src/main/java/com/gme/pay/errors/ErrorCode.java`
- `FORBIDDEN(403, false)`
- `PAYMENT_NOT_FOUND(404, false)`
- `MERCHANT_SUSPENDED(422, false)`
- `MERCHANT_DEACTIVATED(422, false)`
- `PAYMENT_MODE_NOT_SUPPORTED(409, false)`
- `DIRECTION_NOT_ENABLED(409, false)`

Matched existing `(httpStatus, retryable)` ctor style. No existing constants/values changed; consumers' String-literal workarounds (smart-router `ResolutionError`, payment-executor handler, merchant-qr-data) keep compiling — flip them in later steps.

## 2. Event payloads — placed in lib-api-contracts (NOT lib-events)
DECISION: `lib-events` is intentionally the Spring-free / Jackson-free base abstraction (`DomainEvent` interface + `EventPublisher`); its build.gradle has no jackson dep. Canonical serialization-annotated payload DTOs therefore go in `lib-api-contracts` (which has jackson-annotations + money convention) under new subpackage `com.gme.pay.contracts.events`. lib-events left untouched (generic).

New files (`libs/lib-api-contracts/.../contracts/events/`):
- `PaymentApprovedPayload.java` — eventType/aggregateId/txnRef, occurredAt, revenueDate, partnerId, schemeId, collectionMarginUsd, payoutMarginUsd, serviceChargeAmount, serviceChargeCcy, feeSharePct. `EVENT_TYPE="payment.approved"` → topic `gmepay.payment.approved`.
- `TransactionCommittedPayload.java` — EVENT_TYPE `transaction.committed`; rate-locked fields (offerRateColl, crossRate, margins, collection/payout amt+ccy, usdAmount, sameCcyShortcircuit, committedAt).
- `PrefundDeductedPayload.java` — EVENT_TYPE `prefunding.deducted`; amountUsd, balanceAfterUsd, ledgerEntryId, idempotencyKey, at (mirrors deduct endpoint result).
Money/rates = decimal strings via `@JsonFormat(STRING)`.

## 3. lib-api-contracts — projection / summary / prefunding DTOs
- `CommittedFxView.java` — committed-FX projection; field names mirror reporting-compliance `CommittedTransaction` (txnId, txnRef, partnerId, direction[String], sameCcyShortcircuit, offerRateColl, crossRate, collectionAmount, collectionCcy, payoutAmount, payoutCcy, usdAmount, collectionMarginUsd, payoutMarginUsd, committedAt). `direction` is wire String, not enum, to avoid coupling.
- `RevenueSummaryView.java` — canonical `GET /v1/revenue` shape mirroring revenue-ledger's local `RevenueSummaryResponse`, INCLUDING the additive `totalRoundingUsd` (nullable; treat null as zero).
- `PrefundingReserveRequest` / `PrefundingReserveResponse` / `PrefundingReleaseRequest` — OVERSEAS CPM reserve/release (idempotencyKey-keyed; reservationId handle).
- `PrefundingDeductionHistoryView` — wraps `List<BalanceDeductionEntry>` (REUSED — it already fits: amountUsd/at/txnRef) + partnerCode + limit. No new per-row type.

## ⚠️ NAME MISMATCHES found (critical for next wiring steps)
1. **snake_case vs camelCase on events.** _FLEET_STATUS IR-txn-1 specced event fields snake_case (collection_usd, offer_rate_coll, committed_at, …). But the ALREADY-GREEN producer (payment-executor `RestRevenueLedgerClient`/`PaymentOrchestrator`) emits and the consumer (revenue-ledger `PaymentApprovedEventHandler`) reads **camelCase** (`collectionMarginUsd`, `payoutMarginUsd`, `serviceChargeAmount`, `serviceChargeCcy`, `feeSharePct`). I aligned all event/projection DTOs to **camelCase** to match running code. Wiring step must NOT switch to snake_case or it breaks the live revenue path.
2. **`RevenueSummaryResponse` is service-local, not a lib type.** revenue-ledger ALREADY added `totalRoundingUsd` to its own `services/revenue-ledger/.../web/RevenueSummaryResponse.java` (line 19). There was no lib-api-contracts revenue type to extend, so I added a NEW `RevenueSummaryView` (additive, zero call-site impact) for ops-bff/reporting to converge on. ops-bff's `RestRevenueLedgerClient` currently binds its own shape — reconcile in step 6.
3. **No local `TransactionCommittedEvent`/`PrefundDeductedEvent` classes exist** in transaction-mgmt — only `TransactionEntity` columns (prefund_deducted_usd, scheme_txn_ref, etc.) and `collection_amount`/`collection_currency`/`payout_currency`. `CommittedFxView` adds offerRateColl/crossRate/margins/usdAmount NOT yet persisted on `transactions` — step 3 must add those columns + a projection endpoint.
4. **smart-router `ResolutionError` httpStatus = 409** for PAYMENT_MODE_NOT_SUPPORTED / DIRECTION_NOT_ENABLED (its javadoc text saying "400/404" is stale). My new ErrorCode values use **409** to match the actual running mapping. NO_SCHEME_FOR_LOCATION already exists in lib-errors (404) — matches.
5. reporting-compliance `CommittedTransaction` uses `TransactionDirection` enum (INBOUND/OUTBOUND/DOMESTIC/HUB) and `long txnId`/`long partnerId`. `CommittedFxView` uses `String direction` + `long` ids — adapter must `valueOf` the direction string when swapping local→lib.

## Compile status
`./gradlew compileJava compileTestJava --parallel --console=plain` → **BUILD SUCCESSFUL in 13s**, all 17 services + libs green.
