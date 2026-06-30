# Changelog — settlement-reconciliation

All notable changes to this service. Dates are KST.

## [Unreleased]

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
