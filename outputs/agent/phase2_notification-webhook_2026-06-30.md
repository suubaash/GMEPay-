> 작업: Phase2 notification-webhook wiring / 출처: agent

# Phase 2 — notification-webhook: align payment.approved consumer to canonical contract

## Build status
`./gradlew :services:notification-webhook:test` → **BUILD SUCCESSFUL** (broker-free; mocked persistence, no live Kafka/HTTP).

## What aligned
- **Consumer re-targeted to the canonical type.** `PaymentApprovedEventHandler` now
  deserializes `com.gme.pay.contracts.events.PaymentApprovedPayload` (lib-api-contracts,
  camelCase) via a jsr310-aware `ObjectMapper`, dropping the bespoke `JsonNode`
  field-plucking. Producer (payment-executor) and consumer now agree on the wire shape.
- **Topic/type agreement.** `EVENT_TYPE` references `PaymentApprovedPayload.EVENT_TYPE`;
  `PaymentApprovedKafkaConsumer.TOPIC` already resolves to `gmepay.payment.approved`
  (KafkaEventPublisher prefix + `payment.approved`) — no topic change needed.
- **Delivery payload carries the approved-payment fields.** The persisted /
  partner-delivered payload is a normalized re-serialization of the typed event, so
  `txnRef`, `partnerId`, `schemeId`, `collectionMarginUsd`, `payoutMarginUsd`,
  `serviceChargeAmount`, `serviceChargeCcy`, `feeSharePct` are always present (record is
  `@JsonInclude(ALWAYS)`). New JUnit test asserts the captured PENDING-row payload carries them.
- **Defensive null handling kept + extended.** Ref resolution `txnRef → aggregateId →
  record key`, 64-char bound, blank/invalid JSON and eventType/ref gaps stay poison → DLT.
  `FAIL_ON_UNKNOWN_PROPERTIES` disabled to tolerate additive producer fields.
- Dispatcher/drain, WebhookHttpClient, MANUAL-ack consumer, idempotent
  `enqueuePendingIfAbsent` all untouched (Phase 1). CHANGELOG updated. Edits scoped to
  `services/notification-webhook/` only.

## Remaining (≤3)
1. End-to-end broker IT (Testcontainers Kafka) is CI-docker-only; local run still mocks the broker.
2. Partner-facing delivery envelope/signing of the canonical payload not re-verified against partner contract (out of scope here).
3. Other consumers of `gmepay.payment.approved` (revenue-ledger) already on camelCase — no cross-service action needed.
