> 작업: Wave3 scheme-adapter /refunded reconcile / 출처: agent

# Wave-3 RECONCILE — scheme-adapter-zeropay /refunded field-name mismatch

Branch `w3/scheme-adapter-zeropay` off `a36997e`. Edited ONLY `services/scheme-adapter-zeropay/`.
`./gradlew :services:scheme-adapter-zeropay:test` → **BUILD SUCCESSFUL** (15s).

## Mismatch fixed (latent silent-null bug)
`RestTransactionMgmtEnrichmentPort` bound an ad-hoc `RefundedTxnView` record reading
`refundSchemeTxnRef` / `originalSchemeTxnRef` — names transaction-mgmt's real
`GET /v1/transactions/refunded` projection NEVER emits. Jackson bound those to `null`, so refund
enrichment silently dropped the upstream values.

Replaced with the canonical **`com.gme.pay.contracts.RefundedTransactionView`** (lib-api-contracts,
producer's real field names). Added `lib-api-contracts` dependency to build.gradle.

### Fields now mapping REAL values (were null)
- `refundAmountKrw` (BigDecimal, wire String) → ZP0021/ZP0066 refund amount
- `merchantId` → refund-leg merchant on ZP0021/ZP0066 + ZP0061/0063 aggregation
- `qrCodeId` → refund-leg QR
- Map key now `schemeTxnRef` → `originalPaymentTxnRef` → `txnRef` (refund leg's scheme ref matches
  `zp_committed_txns.zeropayTxnRef`).

## Settlement value date — re-verified
Unchanged: still read from `GET /v1/transactions/fx-committed` `settlementDate` (CommittedTxnView).
NOT expected on /refunded — confirmed per step-1 note.

## Tests (MockRestServiceServer; transaction-mgmt NOT running)
- `refundEnrichment_mapsRealAmountMerchantAndQrFromCanonicalView` — canonical producer JSON;
  asserts `refundAmountKrw`/`merchantId`/`qrCodeId` are now non-null (regression on the silent-null).
- `refundEnrichment_fallsBackToOriginalPaymentRefWhenNoSchemeRef` — key-fallback path.
- Existing error/settlement tests still green.

## Remaining (≤3)
1. transaction-mgmt must also return the canonical type (frozen here; cross-service wiring step).
2. `settlementDate` still NULL on /refunded until the producer populates it.
3. Real KFTC/SFTP/IDD widths externally blocked (unchanged).
