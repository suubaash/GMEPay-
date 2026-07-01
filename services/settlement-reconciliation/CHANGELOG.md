# Changelog — settlement-reconciliation

All notable changes to this service. Dates are KST.

## [Unreleased]

### 2026-07-01 — Ops: operator recon re-run + reconciliation-break alert

- **Added** operator recon re-run endpoint `POST /v1/settlements/recon/rerun` (`ReconRerunController` /
  `ReconRerunService`). Body `{batchId}` **or** `{settlementDate}` (exactly one; both/neither → 400,
  unknown batch → 404) plus `operatorId`/`reason`. Re-runs reconciliation for that batch (or every
  ZP0061/ZP0063 request batch of the day), **reusing the existing idempotent `ReconDiffEngine.runDiffForBatch`**
  (delete-then-reinsert on `batchId`), so a re-run never double-posts or duplicates exception lines. Records
  who/why in the audit log and returns the per-batch match/exception summary. Scheme confirmation file is
  re-read via a new shared `ReconFileSource` (extracted from `ReconScheduler`; ZP0061→ZP0062, ZP0063→ZP0064).
- **Added** reconciliation-break alert (`ReconBreakAlerter` + `ReconAlertEvent`): whenever a recon run
  (scheduled or operator) leaves any exception open, emits an `OpsAlertPayload` (`alertType=RECON_BREAK`,
  `subjectRef=batchId`, `detail`=exception summary with break amount, `severity` by blast radius — CRITICAL
  on any MISSING line or ≥₩10M, WARN on ≥5 lines or ≥₩1M, else INFO) via the `EventPublisher` seam on topic
  `gmepay.ops.alert`. Transport selected like the outbox (`ReconAlertConfig`): Kafka when configured, else
  the `LoggingEventPublisher` log-fallback — no broker needed locally. A clean batch emits nothing. Wired
  into both `runDiff` and `runDiffForBatch`.
- **Added** tests (H2 + mocks): re-run is idempotent (two runs → same result, single exception row); a forced
  mismatch emits a `RECON_BREAK` OpsAlert **and** persists the exception; a clean batch emits no alert (batch
  → RECONCILED); severity derivation; scope validation (400/404). `ReconBreakAlerterTest` (6) +
  `ReconRerunServiceTest` (7). Additive; libs and other services untouched. Real SFTP/IDD remain externally blocked.

### 2026-06-30 — Wave-3 RECONCILE: canonical `/refunded` contract + cross-date netting folded in

- **Fixed (latent silent-null bug)** `RestRefundedTransactionClient` bound an ad-hoc wire record
  (`refundTxnRef`/`originalTxnRef`/`refundAmount`(String)/`refundedOn`) whose names did NOT match
  transaction-mgmt's real `/refunded` projection, so Jackson mapped **every** refund leg to `null`
  (original payment ref + amount), making cross-date claw-back netting a silent no-op. Replaced it with
  the canonical `lib-api-contracts` `RefundedTransactionView` (producer field names: `txnRef`,
  `originalPaymentTxnRef`, `refundAmountKrw`, `refundedAt`, `settlementDate`) — refund legs now map REAL
  values.
- **Completed** the netting: `SettlementBatchJobService.runWindow` now folds the refund-date legs into the
  batch net via `foldCrossDateRefunds` — a leg whose **original** payment was settled in a prior batch
  (and not yet clawed back) reduces the merchant's net, is persisted as a negative claw-back line (its own
  refund txnRef = the cross-window idempotency marker), and is reported in the file row (folded into the
  merchant's row when present, else a refund-only GROSS row). Numeric net field stays non-negative
  (claw-back carried in `refund_amount`; batch net reduced by the full magnitude). Added via a new
  `RefundedTransactionPort` constructor arg; a backwards-compatible 9-arg ctor (no-op fixture) keeps
  existing call sites/tests unchanged.
- **Added** tests (MockRestServiceServer; transaction-mgmt not running): refund legs map non-null from
  canonical JSON; a cross-date refund reduces the netted settlement (50000 − 8000 = 42000); an unsettled
  original is not clawed back.

### 2026-06-30 — Phase-2 cross-service wiring (refund-date query + rounding residual post)

Consumes the new shared transaction-mgmt / revenue-ledger Phase-2 contracts (commit 5dbafd5).

- **Added** `RefundedTransactionPort` + gated `RestRefundedTransactionClient` calling transaction-mgmt
  `GET /v1/transactions/refunded?refundedOn=YYYY-MM-DD`, mapping each refund leg incl. the **original
  payment txnRef** (`RefundLeg`) to drive cross-date claw-back netting (settlement IR-1). Gated by
  `gmepay.clients.transaction-mgmt.refunded.enabled` (default false); in-process
  `FixtureRefundedTransactionAdapter` is the fallback. Fails soft to an empty set on transport error.
- **Added** `RoundingResidualPort` + gated `RestRoundingResidualClient` POSTing the Addendum-001
  rounding residual to revenue-ledger `POST /v1/journals/rounding-residual` with **`reference` = the
  settlement batch id** (`ZP00NN-YYYYMMDD-WINDOW`). Gated by `gmepay.clients.revenue-ledger.enabled`
  (default false); in-process `FixtureRoundingResidualAdapter` fallback. Zero residual is a no-op.
- **Wired** the residual post into `ReconDiffEngine.runDiffForBatch`: a batch posts its residual
  **exactly once**, only when this run finalises it to `RECONCILED`. Idempotency guarded by a new
  `settlement_batches.residual_posted_at` column (**V009**) — set only on an accepted post, so a
  transient revenue-ledger failure leaves the batch eligible to retry on the next recon run, and a
  recon re-run never re-posts.
- **Added** JUnit tests (MockRestServiceServer): refund-date client mapping incl. original txnRef +
  fail-soft; residual posted once per batch with batch-id reference; no double-post on re-run; no post
  while a discrepancy holds the batch at RECEIVED; retry-eligible on revenue-ledger failure.
- **Remaining**: `CommittedFxView` consumption for GROSS/FX rate-locked tie-out (no GROSS FX recon path
  exists yet — deferred, not cheap); real SFTP pull + IDD widths remain externally blocked.

### 2026-06-30 — Batch-tied reconciliation pipeline

Closes the "recon pipeline never ties to the batches it actually sent" gap: ZP0062/ZP0064
processing now reconciles against the **persisted outbound settlement batch** instead of a
re-fetch of live transaction gross.

- **Added** `ReconDiffEngine.runDiffForBatch(SettlementBatchEntity, schemeRecords)` — the
  authoritative recon path. GME side is the **net booked amount per merchant**, re-summed from the
  batch's persisted `settlement_lines` (Σ signed amount = payments − clawed-back refunds = the net
  requested in ZP0061/ZP0063). This is what ZeroPay confirms, so a correctly-booked NET batch ties
  out exactly (no more false discrepancy from comparing gross against net).
- **Added** idempotent exception persistence: prior `recon_exceptions` for a batch are deleted
  before re-insert (`ReconExceptionRepository.deleteByBatchId`), so re-processing the same result
  file never duplicates rows (backlog 7.1-T17 / 7.1-T18 acceptance).
- **Added** batch lifecycle advancement on recon: all-MATCHED → `RECONCILED` (matched lines flagged
  `matched=true`); any open exception → `RECEIVED` (received, holding discrepancies for ops). Walks
  only legal `SettlementBatchStatus` transitions; a batch already at/after `RECONCILED` is a no-op.
- **Added** `BatchNotFoundException` (raised for a null/absent batch — backlog 7.1-T18).
- **Changed** `ReconScheduler` to resolve the day's request batch (ZP0062↔ZP0061 MORNING,
  ZP0064↔ZP0063 AFTERNOON) and call the batch-tied path; falls back to the legacy live-txn diff only
  when no request batch exists yet (result file arrived early).
- **Tests** `ReconDiffForBatchTest` (6: net-match→RECONCILED, discrepancy→exception+RECEIVED,
  idempotent re-run, claw-back nets to scheme credit, null→BatchNotFoundException,
  already-RECONCILED no-op) and a new H2 `SettlementPersistenceIT` case exercising the full
  persist→recon→exception→status pipeline over real repositories.

The legacy `ReconDiffEngine.runDiff(batchId, date, records)` live-txn diff is retained as the
no-batch fallback.
