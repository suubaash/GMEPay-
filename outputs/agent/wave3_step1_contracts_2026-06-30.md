> 작업: Wave3 reconcile contracts / 출처: agent

# Wave-3 Step 1 — shared contract definitions (additive)

Branch `w3/contracts` off `main`, worktree `D:/GMEPay+/wt2/w3-contracts`.
ADDITIVE ONLY — no field/enum/signature removed or renamed. Consumers NOT wired (next step).
`./gradlew compileJava compileTestJava --parallel` = **BUILD SUCCESSFUL (all 17 services + libs green)**.

## What was added

### Task 1 — commit-path margin fields (FX1015 accuracy)
The commit/create wire contract is DUPLICATED local records (Jackson name-binding), not a lib type. Added matching fields to BOTH sides, each with a backwards-compatible secondary ctor (old call sites compile unchanged):
- `services/transaction-mgmt/.../api/dto/StatusPatchRequest.java` — +`collectionMarginUsd, payoutMarginUsd, collectionUsd, costRateColl, costRatePay` (BigDecimal, nullable). +8-arg compat ctor.
- `services/transaction-mgmt/.../api/dto/CreateTransactionRequest.java` — +`offerRateColl, crossRate, costRateColl, costRatePay, collectionUsd, payoutUsdCost, collectionMarginUsd, payoutMarginUsd` (IR-txn-2). +12-arg compat ctor.
- `services/payment-executor/.../client/rest/RestTransactionClient.java` — same fields appended to its local `StatusPatchRequest` + `TransactionCreateRequest` records, each with compat ctor so the existing `new …(…)` call sites stay green.

### Task 2 — canonical `RefundedTransactionView`
New `libs/lib-api-contracts/.../contracts/RefundedTransactionView.java`. Mirrors transaction-mgmt's REAL projection field names (`RefundedTransactionResponse`): `txnRef, originalPaymentTxnRef, partnerId, status, merchantId, qrCodeId, schemeTxnRef, refundAmountKrw, targetCcy, merchantFeeRate, refundedAt, approvedAt` + additive nullable `settlementDate` (LocalDate). `@JsonInclude(NON_NULL)`.

### Task 3 — `PartnerSchemeView` location-resolution fields
Existing `libs/lib-api-contracts/.../PartnerSchemeView.java` extended: +`countryCode (String), supportsCpm (Boolean), supportsMpm (Boolean), priority (Integer), status (String)`. `direction` already present. Added 12-arg compat ctor (config-registry `PartnerSchemeEntity.toView` unchanged).

### Task 4 — Rule rate-source fields
Existing `libs/lib-api-contracts/.../RuleView.java` extended: +`rateCollSource (String), ratePaySource (String)` — wire STRING over roster IDENTITY|LIVE|MANUAL|PARTNER. Added 9-arg compat ctor (config-registry `RuleEntity.toView` + `RuleContractsTest` unchanged).

## ⚠️ NAME-MISMATCH warnings (critical for wiring step)
The `GET /v1/transactions/refunded` endpoint has THREE divergent ad-hoc shapes; the new canonical type follows the PRODUCER (transaction-mgmt). Consumers bind WRONG names today → silently null:

| concept | transaction-mgmt (REAL/producer) | settlement-reconciliation ad-hoc | scheme-adapter-zeropay ad-hoc |
|---|---|---|---|
| refund's own ref | `txnRef` | `refundTxnRef` ✗ | `refundSchemeTxnRef` ✗ |
| original payment ref | `originalPaymentTxnRef` | `originalTxnRef` ✗ | `originalSchemeTxnRef` ✗ |
| refund amount | `refundAmountKrw` (BigDecimal) | `refundAmount` (String) ✗ | `refundAmountKrw` ✓ |
| refund date | `refundedAt` (Instant) | `refundedOn` (String) + `refundedAt` | — |

- `settlementDate` is NOT emitted by the real `/refunded` projection — scheme-adapter reads it off a SEPARATE paid-detail endpoint. Added to the canonical view as nullable; producer must populate before consumers rely on it.
- When wiring, swap settlement's `RestRefundedTransactionClient.RefundedTransactionResponse` and scheme-adapter's `RestTransactionMgmtEnrichmentPort` record for `RefundedTransactionView` AND have transaction-mgmt return the canonical type — otherwise the rename breaks current (already-wrong) binding differently.
- Commit margins (task 1) are wire-additive only; transaction-mgmt's persist/`offerRateColl`-derivation still ignores them until the wiring step maps them through `TransactionService` + entity columns.

## Compile status
`./gradlew compileJava compileTestJava --parallel` → BUILD SUCCESSFUL. Only pre-existing `@Deprecated`-removal warnings in ops-partner-bff (unrelated).
