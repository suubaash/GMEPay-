# transaction-mgmt — CHANGELOG

## 2026-06-30 — P1 FSM lifecycle: SCHEME_SENT + UNCERTAIN states

Closes the PRD P1 gap "add UNCERTAIN/REVERSED/REFUNDED FSM states + transitions so
cancel/refund/uncertain PATCH performs a real transition" (REVERSED/REFUNDED were already
present; this wave adds the missing SCHEME_SENT and UNCERTAIN states and the reconciliation
exits).

### Added
- `TransactionStatus.SCHEME_SENT` — scheme adapter dispatched, awaiting response (non-terminal).
- `TransactionStatus.UNCERTAIN` — scheme timeout; prefunding held pending batch reconciliation
  (non-terminal; exits only via reconciliation).
- Transition table edges (`TransactionTransitions`):
  `CREATED→SCHEME_SENT`, `PENDING_DEBIT→SCHEME_SENT`,
  `SCHEME_SENT→{APPROVED,FAILED,UNCERTAIN}`, `UNCERTAIN→{APPROVED,FAILED}`.
- `TransactionService.toSchemeSent`, `toUncertain`, and idempotent `resolveUncertain(txnRef, outcome)`
  (UNCERTAIN→APPROVED/FAILED via ZP0012/ZP0022 reconciliation; no-op if already resolved).
- Flyway `V006__transaction_status_check.sql` — DB CHECK constraint pinning `status` to the 9
  valid enum values (5.1-T08, adapted to this service's enum set).

### Changed
- `TransactionService.mapPaymentStatus` now maps `SCHEME_SENT` and `UNCERTAIN` to real
  `TransactionStatus` values, so a PATCH to those statuses performs an actual FSM transition
  instead of a silent lock-field-only update. `patchStatus` skips the transition when the
  target equals the current status (idempotent re-assert, avoids an illegal self-edge).
- Expiry sweeper (`InMemoryTransactionRepository.SWEEPABLE_STATUSES`) now also sweeps stuck
  `SCHEME_SENT` rows to FAILED on approval timeout; `UNCERTAIN` is deliberately excluded
  (held for reconciliation).

### Tests
- `TransactionTransitionsTest` — new allowed edges (SCHEME_SENT/UNCERTAIN), forbidden
  backward/self edges, outgoing-edge assertions, non-terminal assertions for the two new states.
- `TransactionStateMachineTest` — full OVERSEAS lifecycle, SCHEME_SENT→UNCERTAIN→APPROVED,
  UNCERTAIN→FAILED, UNCERTAIN→CANCELLED blocked.
- `TransactionServiceResolveUncertainTest` (new) — resolveUncertain APPROVED/FAILED/idempotency/bad-outcome.
- `TransactionContractIT` — HTTP-level PENDING_DEBIT→SCHEME_SENT→UNCERTAIN via PATCH.
