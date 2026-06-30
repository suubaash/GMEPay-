# revenue-ledger — CHANGELOG

All notable changes to the revenue-ledger service. Newest first.

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
