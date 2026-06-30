> 작업: settlement-reconciliation backlog 완성 / 출처: agent

# settlement-reconciliation — build report (2026-06-30)

## Build status
`./gradlew :services:settlement-reconciliation:test` — **BUILD SUCCESSFUL** (green). All unit + H2
slice tests pass. New tests: `ReconDiffForBatchTest` (6), `SettlementPersistenceIT` (+1, now 4).

## Assessed real code state
The service is far ahead of the published backlog %. On entry, already real and tested:
net/gross calculators, ZP0061/0063/0065/0066 builders, ZP0062/0064/0012/0022 parsers, `LineMatcher`,
`SettlementBatchJobService` (atomic batch+line persistence, rounding under Addendum-001 per-partner
mode, fee/refund-clawback/window-cutoff, ZP0065/0066 detail tie-out), outbox event, exception API +
persistence (`recon_exceptions` with ops lifecycle), `ReconScheduler` (config-gated, inbox-dir read),
8 Flyway migrations. **Estimate ~88–92% of the buildable (non-externally-blocked) scope.**

The genuine remaining gap was that the recon pipeline **never tied to the batches it sent**: the diff
engine re-summed live transaction **gross** per merchant and compared it to ZeroPay's **net**
confirmation (a guaranteed false discrepancy for any NET batch), re-created exception rows on every
run (not idempotent), and never advanced the batch lifecycle past GENERATED.

## Tickets done this run
1. **Batch-tied recon (core gap)** — `ReconDiffEngine.runDiffForBatch(batch, schemeRecords)`: GME side
   is the net booked amount re-summed from the persisted `settlement_lines` (Σ signed = payments −
   clawed-back refunds), so a correct NET batch ties out exactly against ZP0062/ZP0064.
2. **Recon idempotency** (7.1-T17 / 7.1-T18 acceptance) — `deleteByBatchId` before re-insert; same
   file re-processed never duplicates exception rows.
3. **Batch lifecycle on recon** — all-MATCHED → RECONCILED (matched lines flagged); any mismatch →
   RECEIVED (open exceptions held for ops); legal-state-machine transitions only; RECONCILED is a
   no-op. `BatchNotFoundException` for null/absent batch (7.1-T18).
4. **Scheduler wiring** — `ReconScheduler` resolves the day's request batch (ZP0062↔ZP0061 MORNING,
   ZP0064↔ZP0063 AFTERNOON) and runs the batch-tied path; legacy live-txn `runDiff` retained as the
   no-batch-yet fallback.
5. Tests (recon match/mismatch→exception, batch persist, claw-back net, idempotent re-run, status
   advance) + CHANGELOG.md created.

## Externally blocked (build-to-seam, gated by config — not done here)
- **Real ZeroPay SFTP transport** — scheduler reads a local inbox dir (`recon.inbox-dir`); SFTP pull
  is Phase 2b, gated behind `gmepay.settlement.recon.enabled`. No real KFTC/SFTP/cert per constraints.
- **Final IDD field widths** — ZP0065/0066 builders carry placeholder widths (van_fee, txn_time,
  final column widths) pending the ZeroPay IDD; files are structurally valid + tie-out-correct but
  NOT transmit-ready.
- **REVENUE_ROUNDING posting** of the Addendum-001 residual — residual is computed and carried on
  batch/line, but the GL post lives in revenue-ledger (frozen service); see INTEGRATION REQUEST 2.

## Top remaining (next run)
1. ZP0012/ZP0022 (payment/refund registration result) reconciliation against pre-settlement state —
   parsers exist; no diff path wiring them to txn approval expectations yet.
2. Cross-date refund netting — refund of a prior-day payment nets by original creation date, not
   refund date (documented follow-up in `SettlementBatchJobService`); needs a refund-date txn query
   (see INTEGRATION REQUEST 1).
3. Daily aggregate reconciliation (backlog 7.1-T24): SUM(target_payout) vs ZP0065 totals + count
   tie-out across ZP0061+ZP0063 vs internal batched count.

## INTEGRATION REQUESTS
1. **transaction-mgmt** — expose a refund query keyed by **refund date** (not original-payment
   creation date), e.g. `GET /v1/transactions/refunded?refundedOn=YYYY-MM-DD`, returning the same
   `TransactionRecord` projection plus the original payment's `txnRef`. Needed so a refund of a
   prior-day (already-settled) payment is clawed back in the window it actually occurs, closing the
   documented cross-date netting gap. Adapt locally behind `TransactionQueryPort` once available.
2. **revenue-ledger** — confirm the `postRoundingResidual(ref, residual, ccy)` contract and the
   reference key shape (settlement batch id vs txn ref) so settlement-reconciliation can emit the
   per-batch Addendum-001 residual to `REVENUE_ROUNDING` exactly once. Until wired, the residual is
   persisted on `settlement_batches.rounding_residual` / `settlement_lines.rounding_residual` only.
3. **transaction-mgmt** — confirm whether ZeroPay's ZP0062/ZP0064 net-credit figure is reported
   per-merchant aggregate (current assumption) or per-transaction; if per-txn, the scheme-side
   grouping in `runDiffForBatch` needs to aggregate by merchant before the match (trivial change,
   but the contract must be pinned).
