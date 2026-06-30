> 작업: Phase2 transaction-mgmt wiring / 출처: agent

# Phase 2 — transaction-mgmt committed-FX projection (PRODUCER)

Branch `p2/transaction-mgmt` off integration tip (5dbafd5). Edits confined to
`services/transaction-mgmt/`; shared libs reused unchanged.

## Build status
`./gradlew :services:transaction-mgmt:test` → **BUILD SUCCESSFUL**. 15 new tests, all green
(projection math, refund-date query, event publish, V007 migration round-trip — H2, no Docker).

## Persistence (task 1)
Flyway `V007__committed_fx_projection.sql` adds nullable columns: offer_rate_coll, cross_rate,
collection_margin_usd, payout_margin_usd, usd_amount, same_ccy_shortcircuit, settlement_date,
committed_at + refund enrichment (refund_amount_krw, qr_code_id, refunded_at,
original_payment_txn_ref). Captured best-effort at the APPROVED transition in the state machine;
wrapped so a failure NEVER fails the commit. Refund fields stamped on REFUNDED.

## Endpoints (tasks 2,3)
- `GET /v1/transactions/fx-committed?from&to&partnerId` → `List<CommittedFxView>`.
  offerRateColl = send_amount/(collection_usd − collection_margin_usd) (FX1015 #14);
  crossRate = target_payout/send_amount; null rates for same-ccy short-circuit.
- `GET /v1/transactions/refunded?refundedOn=YYYY-MM-DD` → `List<RefundedTransactionResponse>`
  (TransactionRecord projection + originalPaymentTxnRef + refund enrichment).

## Event (task 4)
`TransactionCommittedEvent` (mirrors `TransactionCommittedPayload`, camelCase, decimal strings)
published via the existing outbox EventPublisher on APPROVED → topic
`gmepay.transaction.committed`. LogEventPublisher / outbox-drain fallback unchanged.

## Refund enrichment (task 5)
refundAmountKrw, qrCodeId, originalPaymentTxnRef, merchantFeeRate, merchantId surfaced on the
refund projection for scheme-adapter / settlement.

## Mismatch / remaining (≤3)
1. Margins absent from the FROZEN PATCH `StatusPatchRequest` → commit-time capture derives
   offerRateColl with zero margin (send_amount/collection_usd). Margin-accurate FX1015 needs
   payment-executor to send margins on PATCH (IR-txn-2) or revenue-ledger to supply them.
2. usdAmount uses prefundDeductedUsd as the send_usd_cost proxy; settlement_date left null
   (no settlement-window source on the commit path yet).
3. CommittedFxView.txnId is a deterministic hash of the UUID txnRef (contract wants long;
   authoritative key remains txnRef) — reporting-compliance must valueOf(direction) string.
