# revenue-ledger — CHANGELOG

All notable changes to the revenue-ledger service. Newest first.

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
