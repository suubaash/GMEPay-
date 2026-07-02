> 작업: CS status/timeline/decline + customer search / 출처: agent

# transaction-mgmt — CS quick-wins

All additive; edits confined to `services/transaction-mgmt/`. Branch `cs/transaction-mgmt`.

## Fields exposed on TransactionResponse
- `failureReason` — existed on the domain aggregate (V004), never serialized; now surfaced, null-safe.
- `statusLabel` — human label mapped from the FSM `TransactionStatus`.
- `declineReasonText` — customer-friendly sentence mapped from `failureReason` (falls back to raw).
- `userRef` — end-customer / wallet id (new, see search below).
- `statusHistory` — now a real ordered timeline (was hard-coded null).

## Status / decline mapping (`CustomerStatusText`, small enum-map)
- CREATED→"Payment created", PENDING_DEBIT→"Awaiting debit", SCHEME_SENT→"Sent to scheme, awaiting
  confirmation", APPROVED→"Payment approved", UNCERTAIN→"Pending verification", FAILED→"Declined",
  CANCELLED→"Cancelled", REVERSED→"Reversed / refunded", REFUNDED→"Refunded".
- Decline codes: APPROVAL_TIMEOUT / SCHEME_REJECTED / INSUFFICIENT_PREFUNDING / TTL_EXPIRED(/EXPIRED)
  → plain sentences; unmapped code → raw reason; null/blank → null.

## How statusHistory is built
DERIVED from timestamps already on the aggregate — no new table.
CREATED(`createdAt`) → APPROVED(`approvedAt`, else `committedAt`) → REVERSED/REFUNDED(`refundedAt`),
plus the current terminal status (FAILED/UNCERTAIN/CANCELLED/…) at `updatedAt`. Entries are
`{status, statusLabel, at, note}`; FAILED's note = decline text, force-resolved = resolution reason.
Sorted oldest-first; never null (fresh CREATED → single entry).

## New search filters (GET /v1/transactions/search and /v1/transactions)
- `reference` → matches the partner's own reference `partnerTxnRef` — ALREADY persisted (V003), no
  new storage.
- `userRef` → end-customer / wallet id — was NOT persisted; NEWLY added: Flyway `V011__add_user_ref.sql`
  (nullable `user_ref` + indexes), captured from `CreateTransactionRequest.userRef`, threaded
  create→domain(`applyUserRef`)→entity/mapper→repository query. Existing filters (txnRef, partnerId,
  schemeTxnRef, merchantId, status, from/to) unchanged; back-compat service/DTO ctors kept.

## Test status
`./gradlew :services:transaction-mgmt:test` — BUILD SUCCESSFUL (all green). New:
`TransactionResponseCsTest` (decline fields + ordered timeline for committed & failed),
`TransactionSearchTest` (+userRef, +reference), `TransactionContractIT` (statusLabel/statusHistory
on GET, userRef & reference search over H2).

Note: shared `~/.gradle/caches/jars-9` was corrupted (locked by pre-existing java processes, path
has a `+`); ran the build against an isolated gradle home to get a clean green.

## Remaining (≤3)
1. `failureReason` has no write path via the public API (only the OI-01 sweeper's
   `applyFailureReason`); PATCH→FAILED does not set it, so `declineReasonText` is null for
   API-driven failures until a reason is supplied.
2. Timeline is milestone-derived (no PENDING_DEBIT/SCHEME_SENT intermediate rows unless current);
   a true per-transition log would need a tracking table (deliberately deferred as not "cheap").
3. `merchantName` / `rateTimestamp` on the DTO remain TODO (out of scope).
