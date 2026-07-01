> 작업: harden txn-mgmt event emission + ShedLock / 출처: agent

# Harden transaction-mgmt — defect #1 (money moves, no ledger impact) + ShedLock (#3)

Branch `fix/transaction-mgmt` (off `fix/contracts`). Edits scoped to
`services/transaction-mgmt/`. Additive; new migration only; libs + other services frozen.

## Defect #1 — operator force-resolve emitted no domain event

`resolveByOperator` transitions an UNCERTAIN txn via the real FSM, which previously
emitted only the internal `TransactionStatusChanged` event on REVERSED — so an operator
reversal released the held prefund float and booked no reversing journal (zero ledger
impact), and no `payment.*` signal reached revenue-ledger / prefunding.

### REVERSED — now emits `payment.reversed`
`TransactionStateMachine.transition(...)` gains a `to == REVERSED` branch (guarded on
non-null `partnerId`, mirroring APPROVED) that appends a new `PaymentReversedEvent` to the
**durable outbox** (topic `gmepay.payment.reversed`). It mirrors the canonical
`PaymentReversedPayload` component-for-component, built from the txn snapshot:
- `txnRef`, `partnerId`, `schemeId`
- reversed **collection-leg** amount + currency
- **`reversedUsd` = `prefundDeductedUsd`** (the USD held at UNCERTAIN → prefunding releases exactly that)
- `reason` = operator's resolution reason (recorded on the aggregate before the transition)
- `source = OPERATOR`, `occurredAt`

The FSM status event is still emitted (additive). Outbox path = never silently dropped.

### COMPLETED — revenue recognised
COMPLETED maps to APPROVED and already routes through the same `transition(...)`, which
emits the revenue-bearing `PaymentApprovedEvent` + `TransactionCommittedEvent` a normal
commit emits. Confirmed and test-locked (no code change needed for this leg).

## ShedLock (#3)
- deps: `shedlock-spring` + `shedlock-provider-jdbc-template` 5.13.0
- migration: **V010__shedlock.sql** (`shedlock` table; engine-neutral PG + H2 PG-mode)
- `ShedLockConfig`: `@EnableSchedulerLock(defaultLockAtMostFor=PT5M)` + a
  `JdbcTemplateLockProvider` on the existing DataSource (`usingDbTime()`)
- `@SchedulerLock` on ALL three `@Scheduled` methods: `ExpirySweeperService.sweep`,
  `StuckTransactionAlertSweeper.sweep`, `OutboxPublisher.publishPending`

## Tests — `./gradlew :services:transaction-mgmt:test` GREEN (122)
- `ForceResolveEventEmissionTest`: REVERSED publishes `PaymentReversedPayload` with
  `reversedUsd`+`reason`+`source=OPERATOR`; COMPLETED publishes the revenue-bearing
  events; idempotent replay emits `payment.reversed` once
- `StuckTransactionAlertSweeperTest`: asserts `@SchedulerLock` on `sweep()`
- full-context `@SpringBootTest` (`TransactionContractIT`) boots ShedLockConfig +
  LockProvider on H2 with the V010 migration — passes

## Files
- `outbox/PaymentReversedEvent.java` (new), `domain/statemachine/TransactionStateMachine.java`
- `config/ShedLockConfig.java` (new), `db/migration/V010__shedlock.sql` (new), `build.gradle`
- `service/ExpirySweeperService.java`, `service/StuckTransactionAlertSweeper.java`, `outbox/OutboxPublisher.java`
- tests: `service/ForceResolveEventEmissionTest.java` (new), `service/StuckTransactionAlertSweeperTest.java`

## Remaining (≤3)
1. `payment.reversed` for the **APPROVED→REVERSED same-day-cancel** path is currently
   skipped (no operator audit) — only the operator UNCERTAIN→REVERSED path emits; wire
   source/reason for the cancel path if that reversal must also release float.
2. `reversedUsd` uses `prefundDeductedUsd`; if the rate-lock-pool `collectionUsd` should
   be authoritative for the release, confirm which figure prefunding expects.
3. Downstream consumers (revenue-ledger reversing journal, prefunding release) of
   `gmepay.payment.reversed` are frozen out of scope — not yet built/verified end-to-end.
