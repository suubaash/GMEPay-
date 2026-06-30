> 작업: Phase2 scheme-adapter-zeropay wiring / 출처: agent

# Phase 2 — scheme-adapter-zeropay cross-service enrichment

## Build status
`./gradlew :services:scheme-adapter-zeropay:test` → **BUILD SUCCESSFUL**. New + existing unit
tests green. No libs / other services touched (frozen). transaction-management mocked over HTTP
(MockRestServiceServer) — not booted.

## Enrichment wired (CONSUMER side)
- **`ZpBatchEnrichmentPort`** (new) — best-effort port; never throws, returns empty maps when
  upstream absent.
- **`RestTransactionMgmtEnrichmentPort`** (new, gated `adapter.zeropay.enrichment.enabled=true`,
  default OFF; mirrors `ZeroPaySchemeApiClient` gating + package-private test ctor):
  - IR-1: binds `GET /v1/transactions/refunded?refundedOn=YYYY-MM-DD` → real
    `refundAmountKrw` + `merchantId` + `qrCodeId` keyed by scheme txnRef. Refund records
    **ZP0021/ZP0066** and per-merchant settlement netting now carry the real amount/merchant
    instead of 0/null (only when the captured row lacks them — captured values otherwise kept).
  - IR-3: binds `GET /v1/transactions/fx-committed?committedOn=YYYY-MM-DD` → committed
    `settlementDate`. **ZP0065/ZP0066** value date = upstream settlementDate, falling back to the
    captured row's date, then business_date.
- **`NoOpZpBatchEnrichmentPort`** (`@ConditionalOnMissingBean`) — in-process fallback (empty maps)
  so local/CI keeps pre-enrichment behaviour. Phase-1 `ZpPersistenceBatchDataPort` (real
  `zp_committed_txns` source) was ENRICHED in place, not rebuilt; one-arg ctor preserves test slices.
- Config: `adapter.zeropay.enrichment.{enabled,transaction-mgmt-base-url}` added; CHANGELOG updated.

## Left as INTEGRATION REQUEST (fees — out of scope here)
- Per-txn `merchantFeeKrw`/`vanFeeKrw` stay `0`. Source is the commission/config side (backlog
  #98), not this adapter. No fee table built; TODO recorded on `ZpPersistenceBatchDataPort`.

## Remaining (≤3)
1. **Fees (IR):** wire `merchantFeeKrw`/`vanFeeKrw` once commission/config publishes per-txn fees.
2. **Wire contract field names:** consumer-owned refund/committed wire DTOs assume
   `refundSchemeTxnRef`/`originalSchemeTxnRef`/`schemeTxnRef` + `settlementDate`; reconcile with
   the producer agent's actual transaction-mgmt projection field names when that lands.
3. **Externally blocked:** real KFTC/SFTP/PGP + final IDD fixed-widths unchanged.
