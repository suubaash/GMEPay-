> 작업: Wave3 transaction-mgmt wiring / 출처: agent

# Wave-3 — transaction-mgmt (producer: FX margins + canonical /refunded)

Branch `w3/transaction-mgmt` off the contract commit (a36997e). Edits confined to
`services/transaction-mgmt/`; libs + other services untouched.

## Build status
`./gradlew :services:transaction-mgmt:test` → **BUILD SUCCESSFUL**, 110 tests green
(H2 + mocks, no Docker). Re-run with `--rerun-tasks` also green.

## Task 1 — margin-accurate FX1015: DONE
- Persist the rate-lock pool: V007 already had `collection_margin_usd`/`payout_margin_usd`;
  added **V008** for `collection_usd`, `cost_rate_coll`, `cost_rate_pay`, `payout_usd_cost`
  (all nullable). New `Transaction.applyRateLockPool(...)` + `applyStatusPatch(...)` overload,
  mapped in entity/mapper, threaded through `createFromPaymentExecutor` + `patchStatus` +
  controller (reads the new `CreateTransactionRequest`/`StatusPatchRequest` pool fields).
- `captureCommittedFxAtCommit` now uses the REAL persisted `collectionMarginUsd` and REAL
  `collectionUsd` (preferred over the `prefundDeductedUsd` proxy) →
  `offerRateColl = send_amount/(collection_usd − collection_margin_usd)`. Zero-margin fallback
  kept ONLY when margins/collectionUsd absent (older rows).
- Ordering fix: `patchStatus` applies lock fields (incl. pool) BEFORE the APPROVED transition,
  so the commit-time FX capture (in the state machine) sees the margins. SM still passes null →
  falls back to the aggregate's persisted values.
- Verified: PATCH margin 6.7308 + collectionUsd 673.0769 → offerRateColl 16282.82959861
  (vs zero-margin 16120.0005…), usdAmount = real 673.0769 not the 999.99 proxy.

## Task 2 — canonical /refunded + settlementDate: DONE (settlementDate wired, null until booked)
- `GET /v1/transactions/refunded` now returns shared `RefundedTransactionView`
  (producer-authoritative names: txnRef, originalPaymentTxnRef, refundAmountKrw, merchantId,
  qrCodeId, schemeTxnRef, refundedAt, settlementDate). Local `RefundedTransactionResponse` left
  in place but unused (dead DTO; not deleted to stay in-scope).
- `settlementDate` is sourced from the aggregate's `settlement_date` (V007 col) and flows
  end-to-end (DB→entity→mapper→aggregate→view). It is **null** today: nothing in this service
  books a settlement window yet, so no value is computed. `@JsonInclude(NON_NULL)` omits it when
  null. Wired so it populates automatically once a window is booked.

## Remaining (≤3)
1. settlementDate stays null until a settlement-window/value-date is actually computed and
   stamped at commit (no source exists in transaction-mgmt yet).
2. Dead `RefundedTransactionResponse.java` can be removed in a cleanup pass (left to avoid
   touching out-of-scope refs).
3. Consumer-side swap (settlement-reconciliation + scheme-adapter binding the canonical
   `RefundedTransactionView`) is those services' Wave-3 task — frozen here.
