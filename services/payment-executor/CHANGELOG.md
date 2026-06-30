# payment-executor — CHANGELOG

All notable changes to the payment-executor service. Newest first.

## [agent/payment-executor] — 2026-06-30

### Added
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
