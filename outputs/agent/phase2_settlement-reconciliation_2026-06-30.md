> 작업: Phase2 settlement-reconciliation wiring / 출처: agent

# Phase 2 — settlement-reconciliation cross-service wiring

Branch `p2/settlement-reconciliation` (off integration tip + shared contracts 5dbafd5). Edited ONLY
`services/settlement-reconciliation/`; libs + other services untouched.

## Build status
`./gradlew :services:settlement-reconciliation:test` → **BUILD SUCCESSFUL**, 122 tests, 0 failures.

## Clients wired (both gated @ConditionalOnProperty + in-process fixture fallback)
1. **Refund-date query (IR-1).** `RefundedTransactionPort` + `RestRefundedTransactionClient` →
   transaction-mgmt `GET /v1/transactions/refunded?refundedOn=`. Maps each leg incl. the **original
   payment txnRef** (`RefundLeg.originalTxnRef`) for cross-date claw-back netting; negative wire
   amounts normalised to positive magnitude. Gate `gmepay.clients.transaction-mgmt.refunded.enabled`
   (default false → `FixtureRefundedTransactionAdapter`, empty set). Fail-soft on transport error.
2. **Rounding residual post.** `RoundingResidualPort` + `RestRoundingResidualClient` → revenue-ledger
   `POST /v1/journals/rounding-residual` with `reference` = settlement batch id. Money as decimal
   string; zero residual = client-side no-op. Gate `gmepay.clients.revenue-ledger.enabled` (default
   false → `FixtureRoundingResidualAdapter`). Wired into `ReconDiffEngine.runDiffForBatch`.

## Residual once-per-batch — confirmed
Posts only when THIS run finalises the batch to RECONCILED; guarded by new
`settlement_batches.residual_posted_at` (**V009**), stamped only on an accepted post. A batch already
RECONCILED on entry is skipped; a recon re-run never re-posts; a revenue-ledger failure leaves the
batch retry-eligible (guard unstamped). Tests assert `postResidual` invoked exactly once with the
batch-id reference across two runs of the same batch.

## Remaining (≤3)
1. `CommittedFxView` (Task 3) NOT consumed — no GROSS/FX rate-locked tie-out path exists in recon yet
   (current tie-out is NET KRW net-amount only); building it is not cheap. Deferred.
2. Cross-date refund netting client is wired + tested, but the netting is not yet folded into the
   batch GME-side sum (settlement lines already carry refund claw-backs per creation date); folding
   refund-date legs into the net is the next step.
3. Real SFTP pull + IDD field widths remain externally blocked.
