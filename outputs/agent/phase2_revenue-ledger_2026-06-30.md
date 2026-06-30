> 작업: Phase2 revenue-ledger wiring / 출처: agent

# Phase 2 — revenue-ledger cross-service wiring

Branch `p2/revenue-ledger` (off integration tip @5dbafd5). Edits confined to `services/revenue-ledger/`; libs untouched.

## Build status
`./gradlew :services:revenue-ledger:test` → **BUILD SUCCESSFUL** (28s). 13 test classes green; new/touched: RevenueControllerTest (3), PaymentApprovedEventHandlerTest (11), RoundingResidualTest (5).

## What was re-targeted / changed
1. **payment.approved consumer → canonical `PaymentApprovedPayload`.** `PaymentApprovedEventHandler`
   now `objectMapper.readValue(payload, PaymentApprovedPayload.class)` (JavaTimeModule;
   FAIL_ON_UNKNOWN_PROPERTIES off) — dropped all bespoke `JsonNode` field-plucking. Field set
   (partnerId, schemeId, collectionMarginUsd, payoutMarginUsd, serviceChargeAmount, serviceChargeCcy,
   feeSharePct) maps 1:1 to `RevenueCaptureService.capture(...)`. Defensive defaults kept: null money→ZERO,
   ccy→"USD", txnRef→aggregateId→recordKey, revenueDate→occurredAt UTC date. Poison→IAE→DLT unchanged;
   bad-money now via InvalidFormatException carrying the field path (existing assertion still passes).
   Added `lib-api-contracts` to build.gradle (was absent).
2. **`GET /v1/revenue` → shared `RevenueSummaryView`** (incl `totalRoundingUsd`). Mapped the local
   aggregate into the canonical lib type so ops-bff + reporting bind one shape. Money now rides as
   decimal STRINGS (view's `@JsonFormat(STRING)`); camelCase field names (no naming strategy was ever
   set — the old DTO's "snake_case" Javadoc was wrong, so no live wire break). Local
   RevenueSummaryResponse retained as value source only.

## Residual-key shape decided (settlement-reconciliation IR-2)
`postRoundingResidual(reference, residual, currency)` — `reference` is an **opaque audit handle**
(ledger col length=64), accepting EITHER a **per-txn ref** (`TXN-…`, payment-executor's per-payment
residual) OR a **settlement batch id** (`ZP00NN-YYYYMMDD-WINDOW`, ≤25 chars — settlement-reconciliation's
per-batch aggregate `batch.roundingResidual`). No code change needed; the contract already supports both.
Documented on RoundingResidualController + added a batch-id test proving verbatim audit on every line.
NOT idempotent on reference — callers post each residual once.

## Remaining (≤3)
1. Posting is not idempotent on `reference`; if settlement-reconciliation may retry, an idempotency
   guard (or unique key on `reference`) should be added later — out of scope, frozen-lib-adjacent.
2. ops-bff `RestRevenueLedgerClient` still binds its own revenue shape — its convergence onto
   `RevenueSummaryView` is the ops-bff side of step 6 (not this worktree).
3. Per-partner rounding attribution: `getRoundingTotalUsd` stays service-wide (rounding journals key
   off reference, not partnerId) — acceptable for the summary; revisit if a per-partner column is wanted.
