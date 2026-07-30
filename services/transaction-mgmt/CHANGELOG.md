# transaction-mgmt — CHANGELOG

## 2026-07-30 — Idempotency keys move to a durable table: transaction-mgmt can run N>1

The `Idempotency-Key` window is the only thing between a partner retry and a **second money
transaction**. It was per-JVM (or, under Helm, a cache) — the last thing holding this service at one
replica.

### Added
- **Flyway `V013__create_idempotency_keys.sql`** — `idempotency_keys (idempotency_key PK,
  response_snapshot, created_at, expires_at)` plus an index for the retention sweep. Engine-neutral
  (PostgreSQL + H2 in PostgreSQL mode), additive.
- **`idempotency/JdbcIdempotencyStore`** — the claim **is the primary key**: `putIfAbsent` attempts
  the `INSERT` rather than reading first, so concurrent duplicates on the same JVM *or on different
  replicas* resolve to exactly one winner with no application-level locking. Expiry is enforced on
  read as well as swept, because a retention job must never be what decides when a key stops being
  replayable.
- **`idempotency/IdempotencyRetentionSweeper`** — hourly, `@SchedulerLock`ed. A table has no TTL, and
  "we will add a sweeper later" is how a money-path table reaches a hundred million rows.
- **`gmepay.idempotency.store`** = `db` (default) | `memory`; anything else **refuses to start**.

### Changed
- **`IdempotencyConfig` rewritten.** It used to mark a Redis store `@Primary` whenever
  `spring.data.redis.host` was set — and the Helm ABI ConfigMap exports that to **every** pod for
  api-gateway's benefit, so this service silently used Redis under Helm and a `ConcurrentHashMap`
  under compose, and nobody chose either. Selection is now explicit, logged, and states the replica
  ceiling it implies.
- `spring.task.scheduling.pool.size` **4 → 5** for the fourth scheduled job. `SchedulerPoolSizeTest`
  pins pool size > job count, so this could not be forgotten.
- **`IdempotencyStore` javadoc corrected.** It claimed "the DB unique constraint on the transaction
  key remains the last-resort backstop". **No such constraint exists** anywhere in V001–V012 — this
  store is the *only* duplicate suppression on the create path, which is precisely why it is now
  durable and shared rather than a cache.

### Removed
- **`RedisIdempotencyStore` and `IdempotencyRedisStoreIT` — DELETED**, and
  `spring-boot-starter-data-redis` removed from `build.gradle`. Redis here was shared but **not
  durable** (`redis:7-alpine`, no AOF, no replication): a restart emptied the 24 h window and a retry
  after it created the duplicate anyway — the same failure, just rarer and harder to reproduce. It
  also made the control able to be *unavailable while the money path was available*, forcing a
  fail-open/fail-closed choice on duplicate suppression where neither answer is good. A row in the
  same database as the transaction it protects cannot be, so the question disappears rather than
  being answered. `redis` is no longer a selectable value, so it cannot return by accident.

### Tests
15 new (176 total, 0 failures). `JdbcIdempotencyStoreTest` runs against a real H2 with the **full
Flyway set** (so V013 is proved to apply on top of V001–V012): a key honoured on replica A is
replayed on B, the same scenario on two per-JVM stores re-executes it and mints a second txnRef,
8 concurrent claimants yield exactly one winner, expiry is enforced on read, a lapsed key is
reclaimable, and the sweep deletes only lapsed rows. `IdempotencyConfigTest` pins the decision table
and the shipped properties.

## 2026-07-03 — Scheme filter on the transaction list/search (feat/scheme-statement-be)

Additive, read-only. Adds an optional `schemeId` filter (maps to the `scheme_id` column, the QR
scheme identity e.g. ZEROPAY/NEPAL) to the transaction list/search so ops-partner-bff can produce a
per-scheme reconciliation statement (owner Goal #6). No migration. Edits confined to
`services/transaction-mgmt/`.

### Added
- **`GET /v1/transactions?schemeId=<id>`** and **`GET /v1/transactions/search?schemeId=<id>`** —
  new optional query param filtering by `scheme_id`. When `schemeId` is null/absent, results are
  exactly as before (no regression). Newest-first (`createdAt DESC`) ordering unchanged.
- Threaded through `TransactionController.list/search/runSearch` →
  `TransactionService.queryTransactions` (new trailing `schemeId` param; a back-compat overload
  delegates with `schemeId=null`) → `TransactionRepository.findByFilters` (port + JPA `@Query`
  gained a `(:schemeId IS NULL OR t.schemeId = :schemeId)` clause).

### Tests
- `TransactionSearchTest`: `schemeId` filter returns only that scheme's txns; null `schemeId`
  returns all (no regression). 6/6 pass offline.

## 2026-07-03 — Delivery-dashboard stats endpoint

Additive, read-only. New product-analytics aggregate over the existing `transactions` rows; no
migration. Edits confined to `services/transaction-mgmt/`.

### Added
- **`GET /v1/transactions/stats?from=<ISO>&to=<ISO>`** (both optional ISO-8601 instants; default
  last 30 days) → `{ window, totals, byPartner, byCorridor, declineReasons }`. "approved" =
  `APPROVED` (V006 CHECK); "declined" = `FAILED/CANCELLED/REVERSED`; corridor = `scheme_id`
  (null → `"UNKNOWN"`); declineReasons uses the real `failure_reason` column (V004), a null reason
  on a declined row labelled by its status; `successRatePct` = round(approved/total*100, 1), 0 when
  total = 0. Backed by single grouped SQL queries (never loads all rows).
- **`GET /v1/transactions/first-approved`** → `{ partnerRef: firstApprovedInstant }` — earliest
  APPROVED `created_at` per partner (activation signal for the ops-partner-bff delivery overview).
- Repository aggregate queries + `TransactionService.computeStats` / `firstApprovedByPartner`
  + `TransactionStatsResponse` DTO. New domain-port methods are `default`-returning-empty so
  existing test fakes keep compiling.

## 2026-07-02 — CS quick-wins: decline reason + plain-language status/timeline + customer search

Customer-support-facing read enrichment on `TransactionResponse` plus two customer-identifier
search filters. All additive; edits confined to `services/transaction-mgmt/`; libs/other services
untouched.

### Added
- **`failureReason` exposed** on `TransactionResponse` (existed on the domain aggregate since V004,
  was never serialized). Null-safe.
- **Plain-language `statusLabel` + `declineReasonText`.** New `CustomerStatusText` maps the FSM
  `TransactionStatus` → a human label (APPROVED→"Payment approved", SCHEME_SENT→"Sent to scheme,
  awaiting confirmation", UNCERTAIN→"Pending verification", REVERSED→"Reversed / refunded",
  FAILED→"Declined", …) and the internal `failureReason` code → a customer-friendly sentence
  (e.g. `APPROVAL_TIMEOUT`→"The payment timed out waiting for the payment network to confirm."),
  falling back to the raw reason for an unmapped code. Both null when their source is null.
- **`statusHistory` wired** (was hard-coded null / TODO). `TransactionResponse.buildStatusHistory`
  DERIVES an ordered `{status, statusLabel, at, note}` timeline from timestamps already on the
  aggregate — no new table: CREATED (`createdAt`) → APPROVED (`approvedAt`/`committedAt`) →
  REVERSED/REFUNDED (`refundedAt`), with the current terminal status (FAILED/UNCERTAIN/CANCELLED/…)
  appended at `updatedAt`; FAILED carries the decline reason as its note, a force-resolved txn its
  resolution reason. Sorted oldest-first; never null (a fresh CREATED txn yields one entry).
- **Customer-identifier search.** `GET /v1/transactions/search` (and `GET /v1/transactions`) gain
  optional `userRef` (end-customer / wallet id) and `reference` (partner's own reference =
  `partnerTxnRef`, already persisted since V003) filters. `reference` needed no new storage;
  `userRef` was NOT persisted → added `user_ref` column (Flyway `V011`), captured from the create
  request (`CreateTransactionRequest.userRef`, nullable) and threaded create→domain→entity→search.
- Flyway `V011__add_user_ref.sql` — nullable `user_ref` column + indexes on `user_ref` and
  `partner_txn_ref` (additive; H2 PG-mode one ALTER per column).

### Tests
- `TransactionResponseCsTest` — failureReason/statusLabel/declineReasonText populated for a FAILED
  txn; APPROVED label + no decline fields; ordered non-null `statusHistory` for a committed
  (CREATED→APPROVED) and a failed txn (terminal FAILED entry carries the decline reason as note).
- `TransactionSearchTest` — search by `userRef` and by `reference` return only the matching txn.
- `TransactionContractIT` — GET exposes `statusLabel` + non-null ordered `statusHistory`
  (CREATED, then +APPROVED after patch); search by `userRef` (round-trips on create) and by
  `reference` finds the txn over H2.
- `./gradlew :services:transaction-mgmt:test` green.

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
