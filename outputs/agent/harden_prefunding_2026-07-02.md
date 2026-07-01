> 작업: harden prefunding release-on-reversal + FLOAT_LOW / 출처: agent

# Harden prefunding — release-on-reversal (#1), FLOAT_LOW ops alert (#5), ShedLock (#3)

Branch `fix/prefunding` (off `fix/contracts`). Edits confined to `services/prefunding/`. Additive-only
migration; Phase-1/2 deduct/reserve/release endpoints untouched; libs FROZEN (only consumed).

## #1 — Release held float on reversal
- New Kafka consumer for topic `gmepay.payment.reversed`: `PaymentReversedKafkaConsumer` (listener) +
  `PaymentReversedEventHandler` (business) + `PrefundingKafkaConsumerConfig` (wiring). Config is
  `@ConditionalOnProperty(spring.kafka.bootstrap-servers)`, MANUAL ack, retry→`.DLT` — mirrors
  revenue-ledger; no broker needed for local/tests.
- Handler deserialises canonical `PaymentReversedPayload`, then calls new
  `PrefundingService.releaseReversedFloat(partnerId, txnRef, reversedUsd)`: credits `reversedUsd` back
  onto the balance + appends a CREDIT ledger row tagged with `txnRef`. That CREDIT-with-txnRef is the
  **same reversal marker** the existing operator `reverse()` uses, so both paths share idempotency: a
  redelivery, or a reversal an operator already booked, finds the existing CREDIT and no-ops (ZERO).
  This closes the leak where force-resolve →REVERSED never returned the held USD.
- Null/absent `reversedUsd` → log + no-op. Missing txnRef/partnerId or bad JSON → poison
  (`IllegalArgumentException`) → DLT. Runs `tierAlerts.afterBalanceChange` so a credit can clear alerts.

## #5 — FLOAT_LOW ops alert (converged, not replaced)
- `TierAlertEvaluator.raise()` still emits `prefunding.alert`; it now ALSO enqueues an
  `OpsAlertPayload`(alertType `FLOAT_LOW`) outbox row → topic `gmepay.ops.alert` on the SAME crossing.
  Existing per-partner stream unchanged.
- Severity by depth below threshold: TIER_95→INFO, TIER_85/TIER_70→WARN, BREACH→CRITICAL.
  `subjectRef`=partner code; `detail`=balance/threshold (decimal strings).
- Lands on the ops topic via the existing outbox drain (eventType `ops.alert` → `gmepay.ops.alert`).

## #3 — ShedLock
- The service has exactly one `@Scheduled`: `OutboxPublisher.publishPending()` (outbox drain).
  Added `@SchedulerLock(name="prefunding-outbox-drain")`; `OutboxConfig` gains `@EnableSchedulerLock`
  + `JdbcTemplateLockProvider`. New `V008__create_shedlock.sql` (additive, H2+PG portable). Ensures a
  single replica drains per tick.

## Test status
`./gradlew :services:prefunding:test` GREEN — 61 tests. New: `PaymentReversedEventHandlerTest` (5,
release/idempotent/null-noop/unknown-throws/poison), `FloatLowOpsAlertTest` (3, INFO/WARN/CRITICAL),
`OutboxPublisherShedLockTest` (1). Adjusted `TierAlertEvaluatorTest` outbox-count assertions to filter
`prefunding.alert` (two outbox rows per crossing now).

## Remaining (≤3)
1. Outbox drain's `toDomainEvent` forwards only eventType/aggregateId/occurredAt — the stored JSON
   payload is NOT carried to Kafka (pre-existing; affects both `prefunding.alert` and the new
   `ops.alert`). Wiring the payload through is a lib-events/drain change, out of this frozen scope.
2. Release-on-reversal credits the event's `reversedUsd`; no cross-check against the original
   RESERVE/CAPTURE amount for that txnRef (event is authoritative by contract).
3. ShedLock verified by unit reflection + single-node boot only; true multi-replica contention is a
   docker/IT concern (no server/docker in scope).
