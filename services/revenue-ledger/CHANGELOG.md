# revenue-ledger — CHANGELOG

All notable changes to the revenue-ledger service. Newest first.

## 2026-06-30 — Wave-3: idempotent rounding-residual posting (w3/revenue-ledger)

### Changed
- **`postRoundingResidual(reference, residual, currency)` is now IDEMPOTENT on `reference`.** A repeat
  post with a reference that already has a rounding journal is a no-op: it returns the existing journal
  (same id) and creates NO second line, so the running `total_rounding_usd` aggregate counts the residual
  exactly once. settlement-reconciliation (per settlement batch id) and payment-executor (per TXN ref)
  can both retry safely regardless of caller-side guards. `LedgerPostingService` pre-checks via the new
  port method `JournalStore.findRoundingResidualByReference`; on a hit it short-circuits before posting.
- Guard is **scoped to the `REVENUE_ROUNDING` account only** — revenue-capture / fee-share / reversal
  journals that carry the SAME `reference` on other accounts are unaffected, so the per-TXN and per-batch
  keying schemes coexist without collision.

### Added
- **Flyway `V006__rounding_residual_idempotency.sql`** — `rounding_residual_keys(reference PRIMARY KEY,
  journal_id, posted_at)` as the DATABASE backstop against a concurrent double-post racing the app-level
  pre-check (the PK trips and rolls back the second writer). A dedicated key table (not a partial /
  expression UNIQUE INDEX) is used because H2 in PostgreSQL MODE — the no-Docker `@DataJpaTest` engine —
  supports neither; a plain PK is portable across H2 and PostgreSQL. `JpaJournalStore.save` inserts the
  guard row in the SAME transaction when (and only when) the journal posts to `REVENUE_ROUNDING`.

### Tests
- `JournalPersistenceIT` (+3, H2): double-post same reference → exactly one journal + same id returned;
  distinct references → distinct journals; retry does NOT double-count the `total_rounding_usd` aggregate.
- `RoundingResidualTest` (+1, unit): repeat post returns the existing journal id.

## 2026-06-30 — Phase 2 cross-service wiring (p2/revenue-ledger)

### Changed
- **Re-targeted the `payment.approved` consumer onto the canonical
  `com.gme.pay.contracts.events.PaymentApprovedPayload`** (lib-api-contracts) — `PaymentApprovedEventHandler`
  now deserializes the event directly into that shared DTO via Jackson (`JavaTimeModule`,
  `FAIL_ON_UNKNOWN_PROPERTIES` off) instead of plucking JSON fields by hand, so payment-executor
  (producer) and revenue-ledger (consumer) agree at the type level. Field set
  (partnerId, schemeId, collectionMarginUsd, payoutMarginUsd, serviceChargeAmount, serviceChargeCcy,
  feeSharePct) maps 1:1 onto `RevenueCaptureService.capture(...)`. Defensive defaults preserved: null
  money → ZERO, serviceChargeCcy → "USD", txnRef → aggregateId → record key, revenueDate → occurredAt
  UTC date. Poison handling unchanged (still `IllegalArgumentException` → DLT); a bad-money value now
  surfaces via `InvalidFormatException` carrying the offending field path. Added `lib-api-contracts`
  as an `implementation` dependency.
- **`GET /v1/revenue` now returns the canonical shared `com.gme.pay.contracts.RevenueSummaryView`**
  (incl. `totalRoundingUsd`) so ops-partner-bff and the reporting revenue board bind one type. The
  service-local `RevenueSummaryResponse` is retained only as the value source. Money rides as decimal
  STRINGs via the view's `@JsonFormat(STRING)` (the prior local DTO emitted unquoted numbers); field
  names unchanged (camelCase — the old "snake_case" Javadoc was inaccurate, no naming strategy was set).

### Confirmed (no code change)
- **Rounding-residual reference-key shape (settlement-reconciliation IR-2).** `postRoundingResidual(reference,
  residual, currency)` takes `reference` as an opaque audit handle (ledger `reference` column `length=64`),
  accepting EITHER a per-txn ref (`TXN-…`, payment-executor) OR a settlement batch id
  (`ZP00NN-YYYYMMDD-WINDOW`, ≤25 chars; settlement-reconciliation's per-batch aggregate residual).
  Documented on `RoundingResidualController`; not idempotent on `reference` (callers post once).

### Tests
- `RevenueControllerTest` (new, 3) — asserts the `RevenueSummaryView` wire shape incl. money-as-string
  and `totalRoundingUsd` (populated + null-coalesced-to-zero), plus the 400 date-range guard.
- `PaymentApprovedEventHandlerTest` — retargeted onto the DTO path (11 green; bad-money assertion still
  surfaces the field name).
- `RoundingResidualTest` — added a batch-id-keyed case proving the reference is audited verbatim on
  every ledger line (IR-2 proof).

## 2026-06-30 — surface REVENUE_ROUNDING in GET /v1/revenue (7.3-T27)

### Added
- `JournalStore.sumRoundingByDateRange(start, end, currency)` — signed net rounding gain/loss over a
  date range (CREDIT adds = gain, DEBIT subtracts = loss). Implemented in both `JpaJournalStore`
  (JPQL aggregate joining `ledger_entries`→`journals` on the `posted_at` window, end-exclusive UTC)
  and `InMemoryJournalStore` (stream fold). Reconciles to the sum of the period's posted residuals.
- `RevenueRecordService.getRoundingTotalUsd(start, end)` and a new `total_rounding_usd` field on the
  `GET /v1/revenue` response (`RevenueSummaryResponse`). `REVENUE_ROUNDING` was already in the chart
  of accounts; this completes T27 by surfacing it in the revenue report.
- `RoundingAggregationTest` — 4 tests (signed reconciliation, currency isolation, out-of-range
  exclusion, zero-not-null) driving the real `LedgerPostingService` + `InMemoryJournalStore`.

## 2026-06-30 — async payment.approved revenue capture (agent/revenue-ledger)

### Added
- **`gmepay.payment.approved` Kafka consumer** (`com.gme.pay.ledger.consumer`) — the async
  ingestion path mandated by `docs/INTER_SERVICE_CONTRACTS.md` ("revenue-ledger consumes
  events payment.approved … async"). Previously only the sync `POST /v1/revenue/capture`
  endpoint existed; this closes the documented event-driven contract.
  - `PaymentApprovedKafkaConsumer` — `@KafkaListener` on `gmepay.payment.approved`, MANUAL ack
    (offset committed only after the revenue row persists), consumer group `revenue-ledger`.
  - `RevenueLedgerKafkaConsumerConfig` — gated on `spring.kafka.bootstrap-servers` (no broker →
    no listener container, so unit slices and the local default stay broker-free). DLT to
    `gmepay.payment.approved.DLT` after 3 total attempts via `DefaultErrorHandler`.
  - `PaymentApprovedEventHandler` — parses the event JSON, maps revenue fields (txnRef→aggregateId
    →record-key fallback; revenueDate→occurredAt-UTC fallback; serviceChargeCcy default USD),
    rejects poison records (bad JSON / wrong eventType / missing txnRef / bad-or-negative money).
- **`RevenueCaptureService`** — single idempotent write path (by `txnRef`) now shared by both the
  sync capture controller and the async consumer, so a transaction captured by either surface
  yields exactly one row. Safe under Kafka at-least-once redelivery.
- `PaymentApprovedEventHandlerTest` — 11 unit tests (happy cross-border, idempotent redelivery,
  same-currency zero-margin, occurredAt date fallback, record-key txnRef fallback, and 6 poison
  cases). Broker-free, runs against the in-memory store wrapped in the real capture service.

### Changed
- `RevenueCaptureController` now delegates to `RevenueCaptureService` (was inlined store logic);
  201 Created on fresh capture, 200 OK on idempotent replay — behaviour unchanged.
- `build.gradle`: `spring-kafka` promoted to `implementation` (the consumer lives in `src/main`).
