> 작업: transaction-mgmt backlog 완성 / 출처: agent

# transaction-mgmt build report — 2026-06-30

## Build / test status
- `./gradlew :services:transaction-mgmt:test --offline --rerun-tasks` → **BUILD SUCCESSFUL** (full rerun, all unit + IT green).
- H2-in-PostgreSQL-mode test scope (no Docker/Postgres locally). Flyway V001–V006 apply cleanly in the DB-backed ITs.

## Tickets completed / advanced this run
Focus: the PRD **P1** gap for this service — "add UNCERTAIN/REVERSED/REFUNDED FSM states + transitions so cancel/refund/uncertain PATCH performs a real transition." REVERSED/REFUNDED already existed; this run added the two missing states and reconciliation exits.

- **5.1-T01 (extend)** — added `TransactionStatus.SCHEME_SENT` + `UNCERTAIN` (non-terminal); `isTerminal()` semantics preserved (APPROVED/FAILED/CANCELLED/REVERSED/REFUNDED).
- **5.1-T02 (extend)** — `TransactionTransitions` new edges: `CREATED→SCHEME_SENT`, `PENDING_DEBIT→SCHEME_SENT`, `SCHEME_SENT→{APPROVED,FAILED,UNCERTAIN}`, `UNCERTAIN→{APPROVED,FAILED}`.
- **5.1-T08** — Flyway `V006__transaction_status_check.sql`: DB CHECK on `status` (9 enum values used by this service; backlog's QUOTED/DEBITED are not this service's vocabulary, so the actual enum set was used).
- **5.1-T17 (lite)** — `TransactionService.resolveUncertain(txnRef, outcome)`: idempotent UNCERTAIN→APPROVED/FAILED for ZP0012/ZP0022 batch reconciliation; no-op when already resolved. Plus `toSchemeSent` / `toUncertain` helpers.
- **PATCH wiring fix** — `mapPaymentStatus` now maps `SCHEME_SENT`/`UNCERTAIN` to real states; `patchStatus` skips self-edges. The PATCH endpoint now performs a real transition for these statuses (was a silent lock-field-only update — the core P1 gap).
- **Expiry sweeper** — `SCHEME_SENT` added to sweepable set (stuck dispatch → FAILED on timeout); `UNCERTAIN` excluded (held for reconciliation).
- **5.1-T09 (extend) / 5.1-T28 (lite)** — tests: TransactionTransitionsTest, TransactionStateMachineTest, new TransactionServiceResolveUncertainTest, TransactionContractIT HTTP-level UNCERTAIN path.

## Backlog completion estimate
The service was already substantially built (CREATED→PENDING_DEBIT→APPROVED/FAILED/CANCELLED/REVERSED/REFUNDED FSM, outbox→Kafka via OutboxAppender+OutboxPublisher, idempotency store, persistence, expiry sweeper, PATCH/GET/POST contract). This run closed the remaining P1 FSM lifecycle gap.

- **WBS 5.1 (state machine):** ~75% — full lifecycle states + transitions + reconciliation resolution + DB CHECK + tests done. Not done: prefunding deduction with SELECT FOR UPDATE (5.1-T11, separate datastore — see IR-1), CPM orchestrator (5.1-T21), dedicated UNCERTAIN 24h alert job (5.1-T24), rate-lock immutability DB trigger (5.1-T18, app-level guard already exists via @Column updatable semantics in domain).
- **WBS 3.3 (8-step event trail / rate_quote / pool-identity):** ~15% — outbox + status-changed event done; the explicit `transaction_event` 8-step trail table, `rate_quote` table, and USD-pool/offer_rate_coll columns are NOT built in this module (the service uses an in-process aggregate + `transactions` table, not the backlog's idealized JPA pool-column schema). See IR-2/IR-3.
- **Overall service backlog (74 tickets):** rough **~55–60%** by value (core lifecycle + API + outbox + idempotency + settlement-rounding lock + merchant-fee snapshot all present).

## Top remaining (highest value)
1. **8-step `transaction_event` append-only trail + `rate_quote` table + USD-pool columns** (WBS 3.3 T01–T20): the biggest gap; needs offer_rate_coll/cross_rate/collection_usd persisted for FX1015 reporting. Currently FX fields on `TransactionResponse` are derived best-effort (appliedFxRate = targetPayout/sendAmount), not rate-locked from a quote.
2. **Prefunding deduction (5.1-T11/T15/T17 reversal)** — needs the prefunding_account datastore (owned elsewhere); see IR-1.
3. **CPM orchestrator + UNCERTAIN 24h alert job** (5.1-T21, 5.1-T24).

## INTEGRATION REQUESTS
1. **lib-events — add `TransactionCommittedEvent` + `PrefundDeductedEvent` (3.3-T06).** This module needs structured committed-transaction + prefund events with snake_case BigDecimal-as-string fields (collection_usd, payout_usd_cost, collection_margin_usd, payout_margin_usd, offer_rate_coll, cross_rate, send_amount, committed_at). `lib-events` is FROZEN; cannot add. Locally we still publish `TransactionStatusChangedEvent` + `PaymentApprovedEvent`. Requesting the two records be added to `lib-events/com.gme.pay.events.transaction`.
2. **rate-fx / config-registry — committed-transaction FX stream contract.** To expose a rate-locked committed stream with FX1015 `offer_rate_coll` (PRD P1 item 2), this service needs the rate_quote/treasury snapshot fields (offer_rate_coll, cross_rate, cost_rate_coll, cost_rate_pay, collection_usd, payout_usd_cost) supplied at create/commit time from the rate engine. Currently POST /v1/transactions carries only collectionAmount/targetPayout/merchantFeeRate. Requesting the create contract (lib-api-contracts `TransactionCreateRequest`) be extended with the rate-lock pool fields so they can be persisted and re-emitted for reporting.
3. **prefunding-account service — deduction/reversal API.** 5.1-T11/T15/T17 require atomic prefunding debit (SELECT FOR UPDATE) and reversal on FAILED reconciliation/cancel. This balance lives in another service's datastore (MSA rule: no cross-DB access). Requesting a `POST /internal/v1/prefunding/{partnerId}/deduct` and `/reverse` API (or an event contract) so transaction-mgmt can drive PENDING_DEBIT→DEBITED and reversal without owning the balance.

## Notes
- Package is `com.gme.pay.txn` (backlog says `txnmgmt`); migrations are V00x (backlog says V9–V13). Implementations follow the existing repo conventions, not the backlog's idealized names, to stay green and additive.
- Domain uses an in-process `Transaction` aggregate + `TransactionEntityMapper` → `transactions` table (not per-table JPA entities). All new work fits this design.
