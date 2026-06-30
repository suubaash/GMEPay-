> 작업: Phase2 prefunding wiring / 출처: agent

# Phase 2 — prefunding cross-service wiring (PRODUCER)

Branch `p2/prefunding`, off integration tip with the new shared contracts (commit 5dbafd5).
`./gradlew :services:prefunding:test` = **BUILD SUCCESSFUL**, 45 tests green (4 new CPM + 3 new history). Libs untouched.

## Endpoints added
1. `GET /v1/prefunding/{code}/deductions?limit=N` → `PrefundingDeductionHistoryView`
   (most-recent-first `BalanceDeductionEntry` list; limit default 20, clamped 1..500; unknown partner ⇒ empty).
   Unblocks payment-executor balance `?include_history=true` (IR-pe-2).
2. `POST /internal/v1/prefunding/{partnerId}/reserve` (`PrefundingReserveRequest` → `PrefundingReserveResponse`)
   — soft-holds funds at CPM/QR token issuance; idempotent on `idempotencyKey`; returns `reservationId`
   (RESERVE ledger id) + availableUsd + total reservedUsd. 402 INSUFFICIENT_PREFUNDING on overdraw.
3. `POST /internal/v1/prefunding/{partnerId}/release` (`PrefundingReleaseRequest`) — frees hold on
   expiry/decline; idempotent on `idempotencyKey`; no active hold ⇒ 0 no-op. (IR-qr-3)

## Implementation notes
- New service methods `reserveForCpm` / `releaseForCpm` reuse the same per-partner `SELECT FOR UPDATE`
  lock + RESERVE/RELEASE ledger entries and the `available = balance + credit_limit − reserved` invariant
  as the existing two-phase reserve/capture/release — did NOT touch the Phase-1 `deduct`/`reverse` or the
  txnRef-keyed `reserve`/`release`.
- Path `{partnerId}`/`{code}` is the partner-code String PK (provision stores partnerCode as partner_id);
  contract `long partnerId` is echoed back in the reserve response.
- Money serializes per MONEY_CONVENTION (decimal strings); DB NUMERIC(20,8) ⇒ persisted reads are scale-8.

## Remaining (≤3)
1. capture-on-approval: a CPM reserve currently converts to a hard charge only via the existing txnRef-keyed
   `/capture` — confirm qr→executor uses the same key so a reserved hold captures rather than double-deducting.
2. `reservationId` is the RESERVE ledger id (String); release keys on `idempotencyKey`, not `reservationId` —
   if a consumer only retains `reservationId`, add a reservationId→hold lookup.
3. config-registry `PUT .../credit-limit` push (IR-pf-2) still per-request, not yet pushed.
