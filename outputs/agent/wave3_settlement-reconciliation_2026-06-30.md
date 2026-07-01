> 작업: Wave3 settlement /refunded reconcile + netting / 출처: agent

# Wave-3 RECONCILE — settlement-reconciliation `/refunded` field-name mismatch + cross-date netting

## Build status
`./gradlew :services:settlement-reconciliation:test` → **BUILD SUCCESSFUL** (all tests green, incl. 3 client + 2 new cross-date batch tests). Worktree `wt3/settlement-reconciliation` @ branch `w3/settlement-reconciliation` off contract commit `a36997e`. Edited ONLY `services/settlement-reconciliation/`; libs + other services untouched.

## Mismatch fixed
`RestRefundedTransactionClient` bound an ad-hoc wire record (`refundTxnRef`/`originalTxnRef`/`refundAmount`(String)/`refundedOn`) that did NOT match transaction-mgmt's real `/refunded` projection → Jackson nulled **every** refund leg (original-payment ref + amount), making cross-date claw-back netting a silent no-op. Replaced the ad-hoc record with the canonical `RefundedTransactionView` (`txnRef`, `originalPaymentTxnRef`, `refundAmountKrw` BigDecimal, `refundedAt` Instant, `settlementDate` LocalDate). `toRefundLeg` now maps REAL values; refund date prefers `settlementDate`, else the `refundedAt` date, else the query date.

## Netting folded
`SettlementBatchJobService.runWindow` now calls `foldCrossDateRefunds(date)`: fetches refund-DATE legs, claws back only legs whose **original payment** has a prior positive settled line and no prior negative line (idempotency marker = the refund's own txnRef), persists a negative claw-back line, and **reduces the batch net** by the full magnitude. File row: folded into the merchant's existing row when present, else a refund-only GROSS row; the numeric net field stays non-negative (`num()` forbids minus), claw-back carried in `refund_amount`. Injected via a new `RefundedTransactionPort` ctor arg; a backwards-compatible 9-arg ctor (no-op `FixtureRefundedTransactionAdapter`) keeps existing tests/call sites unchanged.

Tests: `crossDateRefundReducesNet` (50000 − 8000 = 42000), `crossDateRefundNotClawedWhenOriginalUnsettled` (unchanged), updated client test JSON to canonical names asserting non-null mapping.

## Remaining (≤3)
1. transaction-mgmt must actually populate `settlementDate` on the `/refunded` projection (currently null until producer wiring) — until then refund date falls back to `refundedAt`/query date.
2. Refund-only-merchant / negative-net file emission still clamps the numeric net at 0 (ZeroPay negative-settlement handling + IDD field widths externally blocked).
3. The `settlement.completed` event txn count still reflects creation-date records only (cross-date legs not added to that informational count; file recordCount is correct).
