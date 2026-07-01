> 작업: Ops recovery+search (transaction-mgmt) / 출처: agent

# Ops capabilities in transaction-mgmt

Three Operations features, additive only, confined to `services/transaction-mgmt/`. Branch
`ops/transaction-mgmt` off `ops/contracts`. `./gradlew :services:transaction-mgmt:test` green.

## 1. Force-resolve UNCERTAIN
- `POST /v1/transactions/{txnRef}/resolve` — body `{resolution: COMPLETED|REVERSED, reason, operator}`.
- Transitions via the real FSM: `COMPLETED`→APPROVED, `REVERSED`→REVERSED. New FSM edge
  `UNCERTAIN→REVERSED` (additive to `TransactionTransitions`).
- Records `reason`/`operator`/`resolvedAt` on the aggregate audit (`Transaction.applyOperatorResolution`,
  persisted via Flyway `V009__operator_resolution_audit.sql` — nullable `resolution_reason`/`resolved_by`/`resolved_at`).
- Idempotent: a repeat once the txn is already in the resolved terminal state returns it unchanged
  (no re-transition/no duplicate event). Rejects any txn that is not UNCERTAIN; validates
  resolution/reason/operator.

## 2. Stuck/aged alert sweep
- `StuckTransactionAlertSweeper` — `@Scheduled` (fixedDelay, KST), config-gated
  `gmepay.txn.stuck-alert.enabled` **default off**.
- Finds txns stuck in a non-terminal state (default `UNCERTAIN`; configurable to add
  `PENDING_DEBIT`/`SCHEME_SENT`) with `updatedAt` older than `threshold-seconds` (default 900).
- Emits `OpsAlertPayload` — alertType `UNCERTAIN_AGED` (else `STUCK_TXN`), severity WARN, escalating
  to CRITICAL past `critical-multiplier`× (default 4), subjectRef = txnRef — via the existing outbox
  `EventPublisher` seam → topic `gmepay.ops.alert`; `LoggingEventPublisher` fallback when no broker.
- New `OpsAlertEvent` DomainEvent (eventType `ops.alert`) + `TransactionRepository.findStuck`. Read-only:
  never mutates the txn (recovery is the operator's job via feature 1).

## 3. 360° transaction search
- `GET /v1/transactions/search` + the existing `GET /v1/transactions` extended (not duplicated) with
  optional filters `txnRef`, `partnerId`, `schemeTxnRef`, `status`, `merchantId`, `from`/`to`.
- Returns the paged `TransactionResponse` projection; blank filters ignored. JPA `findByFilters`
  extended with the three exact-match filters.

## Test status
All green. New: `TransactionServiceForceResolveTest` (5 — REVERSED w/ reason+audit, COMPLETED,
idempotent, reject non-UNCERTAIN, reject bad input), `StuckTransactionAlertSweeperTest` (3 — emits
UNCERTAIN_AGED ops.alert via publisher, CRITICAL escalation, disabled no-op), `TransactionSearchTest`
(2 — merchantId filter, no-filter). `TransactionTransitionsTest` updated for the new edge.

## Remaining / deferred
1. Alert dedupe — the sweep re-alerts a still-stuck row every tick; a per-subject cooldown /
   already-alerted marker would cut noise (out of scope here).
2. `TransactionResponse` does not yet surface the new `resolution_reason`/`resolved_by`/`resolved_at`
   in the read projection (recorded + queryable, not exposed on the wire).
3. Live wiring of `gmepay.ops.alert` consumption (ops dashboard / monitor) is a separate wave.
