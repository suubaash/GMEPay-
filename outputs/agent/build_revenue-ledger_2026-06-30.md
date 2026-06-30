> 작업: revenue-ledger backlog 완성 / 출처: agent

# revenue-ledger build report — 2026-06-30

**Branch:** `agent/revenue-ledger`  ·  **Build status:** `./gradlew :services:revenue-ledger:test` **GREEN** (BUILD SUCCESSFUL)

## Summary

The service was found further along than the backlog text implies: the PRD-flagged P1 gap
("postRevenueCapture/postFeeShareSplit invoked by nothing, RevenueRecordStore in-memory → /v1/revenue
returns zeros") was **already largely closed** before this run — `JpaRevenueRecordStore` is `@Primary`,
the sync `POST /v1/revenue/capture` endpoint exists, and `RoundingResidualController` +
`postRoundingResidual` + the reversal journal are all built and committed.

This run closed the two highest-value **remaining** gaps:

1. The **documented async ingestion path** (`docs/INTER_SERVICE_CONTRACTS.md`: "revenue-ledger consumes
   events `payment.approved` … async" / "payment-executor ==> Kafka payment.approved → revenue-ledger
   margin + fee capture"). Only the sync endpoint existed; there was **no Kafka consumer**.
2. **7.3-T27** — `REVENUE_ROUNDING` was in the chart of accounts but **not surfaced in `GET /v1/revenue`**.

## Tickets done this run

### 1. payment.approved Kafka consumer (async revenue capture) — INTER_SERVICE_CONTRACTS async path
- `consumer/PaymentApprovedKafkaConsumer` — `@KafkaListener` on `gmepay.payment.approved`, MANUAL ack
  (offset committed only after the revenue row persists), group `revenue-ledger`.
- `consumer/RevenueLedgerKafkaConsumerConfig` — gated on `spring.kafka.bootstrap-servers` (no broker →
  no listener container; unit slices + local default stay broker-free, outbox path untouched). DLT to
  `gmepay.payment.approved.DLT` after 3 total attempts (mirrors the notification-webhook reference).
- `consumer/PaymentApprovedEventHandler` — defensive JSON→revenue mapping (txnRef→aggregateId→record-key
  fallback; revenueDate→occurredAt-UTC fallback; serviceChargeCcy default USD), rejects poison records
  (bad JSON / wrong eventType / missing txnRef / non-numeric or negative money).
- `revenue/RevenueCaptureService` — extracted single idempotent write path (by `txnRef`) now shared by
  BOTH the sync controller and the async consumer → exactly one row per txn regardless of surface;
  safe under Kafka at-least-once redelivery. `RevenueCaptureController` refactored onto it (201/200
  behaviour unchanged).
- Tests: `PaymentApprovedEventHandlerTest` (11, broker-free), existing `RevenueCaptureControllerTest`
  updated and green (3).
- `build.gradle`: `spring-kafka` promoted to `implementation` (consumer lives in `src/main`).

### 2. 7.3-T27 — surface REVENUE_ROUNDING in GET /v1/revenue
- `JournalStore.sumRoundingByDateRange(start, end, currency)` — signed net (CREDIT=gain adds,
  DEBIT=loss subtracts) over a journal `posted_at` date window. Implemented in `JpaJournalStore`
  (JPQL aggregate joining `ledger_entries`→`journals`, end-exclusive UTC instants) and
  `InMemoryJournalStore` (stream fold).
- `RevenueRecordService.getRoundingTotalUsd` + new `total_rounding_usd` field on
  `RevenueSummaryResponse` / wired through `RevenueController`. Reconciles to the sum of posted residuals.
- Tests: `RoundingAggregationTest` (4) — signed reconciliation, currency isolation, out-of-range
  exclusion, zero-not-null. `RoundingResidualTest`'s local `JournalStore` stub updated for the new
  port method.

## Build / test status
- `./gradlew :services:revenue-ledger:test` → **BUILD SUCCESSFUL**.
- New/changed test classes all green: PaymentApprovedEventHandlerTest 11, RoundingAggregationTest 4,
  RevenueCaptureControllerTest 3, RoundingResidualTest 4. No skips/failures/errors.
- H2 test scope (per brief). Docker-tagged ITs (Testcontainers PG/Kafka) are CI-only and unchanged.

## Completion estimate
Service backlog (WBS 7.2 + 7.3 + v3 gap-closure): **~88%**. Core money logic (double-entry posting,
70/30 + configurable commission split, FX-margin + service-charge capture, rounding residual + reversal
journals, JPA persistence, outbox→Kafka), both ingestion surfaces (sync endpoint + async consumer),
and rounding reporting are done. Remaining is mostly reporting-polish + externally-gated items.

## INTEGRATION REQUESTS
1. **payment-executor**: publish a `payment.approved` domain event to Kafka topic
   `gmepay.payment.approved` carrying the revenue fields this consumer reads: `eventType`
   ("payment.approved"), `aggregateId`/`txnRef`, `occurredAt` (or explicit `revenueDate`),
   `partnerId`, `schemeId`, `collectionMarginUsd`, `payoutMarginUsd`, `serviceChargeAmount`,
   `serviceChargeCcy`, `feeSharePct` (money as decimal strings). The consumer is defensive (missing
   numerics default to 0, ccy defaults USD) but partnerId/schemeId/margins must be populated for
   `GET /v1/revenue` aggregates to be meaningful. Until this event ships, the sync
   `POST /v1/revenue/capture` endpoint remains the live path.
2. **lib-api-contracts (shared-libs, FROZEN)**: formalise the `payment.approved` event schema and the
   `RevenueCaptureRequest` / revenue-summary DTOs (incl. the new `total_rounding_usd` field) as an
   OpenAPI/event contract so payment-executor and revenue-ledger agree at the type level rather than
   via this consumer's hand-rolled JSON parsing.
3. **Ops Admin BFF / reporting-compliance**: `GET /v1/revenue` now returns `total_rounding_usd`
   (net rounding gain/loss, USD, per date range). Downstream revenue-board consumers should add the
   column. Rounding is keyed by txn reference (service-wide for the period), not per-partner.

## Remaining (top items)
1. **7.3-T25** — group revenue summary by `service_charge_ccy` (avoid summing KRW+USD); add per-ccy
   rows + `serviceChargeCcy` already partly present on the aggregate. (~35m)
2. **7.2-T13/T14** — `SchemeFeeSplitQueryService` aggregate-by-scheme/partner + internal
   `/internal/v1/fee-share/summary` endpoints (FINANCE/SUPER_ADMIN). Calculator + split exist; the
   query/reporting + admin endpoints are not built here. (~80m)
3. **7.2-T15/T16/T17** — monthly fee-share reconciliation flag + admin fee-config upsert API + audit
   log. (Depends on a `scheme_fee_config` table not present in this service's migrations.)

Note: many 7.2 tickets assume a `scheme_fee_config`/`transaction_fee_snapshot` schema and a
transaction-commit flow that live in transaction-mgmt/config-registry, not revenue-ledger; they are
cross-service and out of this worktree's frozen scope.
