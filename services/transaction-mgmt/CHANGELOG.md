# transaction-mgmt — CHANGELOG

## 2026-07-01 — Ops: force-resolve UNCERTAIN + stuck-txn alerts + 360° search

Adds three Operations capabilities. Edits confined to `services/transaction-mgmt/`; shared
`OpsAlertPayload` contract (from `ops/contracts`) reused unchanged.

### Added
- **Force-resolve UNCERTAIN.** `POST /v1/transactions/{txnRef}/resolve` body
  `{resolution: COMPLETED|REVERSED, reason, operator}` → transitions an UNCERTAIN txn to a
  terminal state via the real FSM (`COMPLETED`→APPROVED, `REVERSED`→REVERSED), recording
  `reason`/`operator`/`resolvedAt` in the transaction audit. Idempotent (repeat once resolved
  returns the resolved state); rejects a non-UNCERTAIN txn. `TransactionService.resolveByOperator`;
  new FSM edge `UNCERTAIN→REVERSED`; `Transaction.applyOperatorResolution(...)`.
- **Stuck/aged alert sweep.** `StuckTransactionAlertSweeper` — `@Scheduled`, config-gated
  (`gmepay.txn.stuck-alert.enabled`, **default off**). Finds txns stuck in a non-terminal state
  (default `UNCERTAIN`; configurable to add `PENDING_DEBIT`/`SCHEME_SENT`) older than
  `threshold-seconds` (default 900) and emits an `OpsAlertPayload` (alertType `UNCERTAIN_AGED`/
  `STUCK_TXN`, severity WARN→CRITICAL past `critical-multiplier`×, subjectRef=txnRef) via the
  existing outbox `EventPublisher` seam → topic `gmepay.ops.alert`; `LoggingEventPublisher`
  fallback when no broker. New `OpsAlertEvent` DomainEvent + `TransactionRepository.findStuck`.
- **360° search.** `GET /v1/transactions/search` (and the existing `GET /v1/transactions`
  extended, not duplicated) with optional filters `txnRef`, `partnerId`, `schemeTxnRef`, `status`,
  `merchantId`, `from`/`to` → paged `TransactionResponse` projection for operator drill-down.
- Flyway `V009__operator_resolution_audit.sql` — nullable `resolution_reason` / `resolved_by` /
  `resolved_at` columns (additive).

### Tests
- `TransactionServiceForceResolveTest` — UNCERTAIN→REVERSED with reason+operator audit,
  COMPLETED→APPROVED, idempotent repeat, reject non-UNCERTAIN, reject bad input.
- `StuckTransactionAlertSweeperTest` — emits an `UNCERTAIN_AGED` ops.alert for an aged UNCERTAIN
  txn (asserts EventPublisher publish + canonical `ops.alert` payload), CRITICAL escalation,
  disabled-sweep no-op.
- `TransactionSearchTest` — merchantId filter returns only matching rows; no-filter returns all.
- `TransactionTransitionsTest` — UNCERTAIN outgoing edges now include REVERSED.

## 2026-06-30 — Wave-3: margin-accurate FX1015 + canonical /refunded (producer)

Wires the Wave-3 shared contracts (commit a36997e). Edits confined to
`services/transaction-mgmt/`; lib contracts reused unchanged.

### Added
- Flyway `V008__rate_lock_pool.sql` — nullable rate-lock pool columns `collection_usd`,
  `cost_rate_coll`, `cost_rate_pay`, `payout_usd_cost` (`collection_margin_usd` /
  `payout_margin_usd` already existed in V007).
- `CreateTransactionRequest` / `StatusPatchRequest` pool fields now persisted: new
  `Transaction.applyRateLockPool(...)` + extended `applyStatusPatch(...)` overload, mapped
  in `TransactionEntity` / `TransactionEntityMapper`, threaded through
  `TransactionService.createFromPaymentExecutor` + `patchStatus` and the controller.

### Changed
- **Margin-accurate FX1015 #14.** `captureCommittedFxAtCommit` now derives
  `offerRateColl = send_amount / (collection_usd − collection_margin_usd)` from the REAL
  persisted `collectionMarginUsd` and the REAL `collectionUsd` (preferred over the
  `prefundDeductedUsd` proxy). Zero-margin fallback retained only when margins/collectionUsd
  are absent (older rows). `patchStatus` now applies the lock fields (incl. pool) BEFORE the
  APPROVED transition so the commit-time capture sees the margins.
- **Canonical `/refunded`.** `GET /v1/transactions/refunded` now returns the shared
  `com.gme.pay.contracts.RefundedTransactionView` (producer-authoritative field names) instead
  of the local `RefundedTransactionResponse`, so settlement-reconciliation + scheme-adapter
  bind one type and stop silently null-binding their divergent ad-hoc records. `settlementDate`
  is sourced from the aggregate's settlement-window field; null until a window is booked.

### Tests
- `CommittedFxMathTest`: persisted margin + real `collectionUsd` → margin-accurate
  `offerRateColl` (16282.82959861, non-zero margin); legacy-row zero-margin fallback.
- `TransactionContractIT`: PATCH carrying margin+collectionUsd → margin-accurate
  `offerRateColl`/`usdAmount` on `/fx-committed`; `/refunded` returns the canonical view shape
  (asserts the ad-hoc divergent names are absent).

## 2026-06-30 — Phase 2: committed-FX projection (producer)

Wires transaction-mgmt as the producer of the committed-FX projection consumed by
reporting-compliance (BOK FX1015 #14), settlement-reconciliation, scheme-adapter and
revenue-ledger. Edits confined to `services/transaction-mgmt/`; shared lib contracts
(`CommittedFxView`, `TransactionCommittedPayload`) reused unchanged.

### Added
- Flyway `V007__committed_fx_projection.sql` — committed-FX columns (`offer_rate_coll`,
  `cross_rate`, `collection_margin_usd`, `payout_margin_usd`, `usd_amount`,
  `same_ccy_shortcircuit`, `settlement_date`, `committed_at`) + refund-enrichment columns
  (`refund_amount_krw`, `qr_code_id`, `refunded_at`, `original_payment_txn_ref`); indexed on
  `committed_at` / `refunded_at`. All nullable, captured best-effort at commit.
- `GET /v1/transactions/fx-committed?from&to&partnerId` → `List<CommittedFxView>`.
  `offerRateColl = send_amount/(collection_usd − collection_margin_usd)` (FX1015 #14),
  `crossRate = target_payout/send_amount` (subash-fx). Null rates for same-currency short-circuit.
- `GET /v1/transactions/refunded?refundedOn=YYYY-MM-DD` → `List<RefundedTransactionResponse>`
  (TransactionRecord projection + original payment txnRef + refund enrichment).
- `TransactionCommittedEvent` (DomainEvent) published via the existing outbox EventPublisher on
  APPROVED → topic `gmepay.transaction.committed`; LogEventPublisher fallback unchanged for no-Kafka.
- Domain capture: `Transaction.captureCommittedFxAtCommit(...)` + static `computeOfferRateColl` /
  `computeCrossRate` helpers; `applyRefundEnrichment(...)`. State machine stamps `committed_at`/FX
  on APPROVED and `refunded_at` on REFUNDED — both wrapped so a projection/event failure NEVER
  fails the commit/transition path.
- JUnit: projection math (incl. offerRateColl + same-ccy null + zero-margin collapse),
  refund-date query, event publish on APPROVED, V007 migration round-trip (H2, no Docker).

### Notes / remaining
- Margins are not on the frozen PATCH `StatusPatchRequest` contract, so the commit-time capture
  derives `offerRateColl` from the `prefundDeductedUsd` USD pool with zero margin; margin-aware
  values require payment-executor to send margins on PATCH (or a richer commit endpoint).
- `usdAmount` uses `prefundDeductedUsd` as the `send_usd_cost` proxy.

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
