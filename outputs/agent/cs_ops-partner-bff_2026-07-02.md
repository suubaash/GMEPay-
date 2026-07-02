> 작업: CS support-read surface / 출처: agent

# CS support-read surface — ops-partner-bff

Additive; branch `cs/ops-partner-bff` off `main`. Only `services/ops-partner-bff/` touched. Libs + other services frozen.

## Search params forwarded
`GET /v1/admin/transactions/search` now forwards to transaction-mgmt's `GET /v1/transactions/search`:
- **`userRef`** (end-customer / wallet id) and **`reference`** (partner's own reference) — both new, optional.
- **`q`** is no longer silently dropped: retained as the free-text term (transaction-mgmt matches it against `txnRef`). `q` + `status` + `partnerId` all still forwarded.
- `SearchQuery` record grew `userRef`/`reference`; a back-compat `search(q,partnerId,status,page,size)` overload keeps `ControlTowerController` + fakes compiling.

## Fields surfaced (null-safe)
Search/detail responses now carry `failureReason`, `statusLabel` (plain language), `declineReasonText`, and an ordered `statusHistory` (`{status,statusLabel,at,note}`), mapped from transaction-mgmt's `TransactionResponse`. Added to `TransactionSummary` + `TransactionDetail` (both admin + portal detail builders); `StatusEntry` enriched `{status,at}`→`{status,statusLabel,at,note}` with a `StatusEntry.of` back-compat factory. `@JsonInclude(NON_NULL)` keeps older txns / consumers unaffected.

## txn.view vs ops:operate split
New `OpsRbacGuard.requireTxnView` (fail-closed on **`txn.view`**; `ops:operate` also accepted so an operator can read). Applied to the CS **read** endpoints: `/transactions/search`, `/transactions`, `/transactions/recent`, `/transactions/{id}`. The `ops:operate` gate on money/state actions (resolve, pause/resume, maintenance, alert-ack…) is **unchanged**. Net: `txn.view` ⇒ look up + read; `ops:operate` still required for force-resolve / kill-switch.

## Tests
`./gradlew :services:ops-partner-bff:test` → **BUILD SUCCESSFUL** (green). Added/updated:
- search forwards `userRef`/`reference` upstream (MockRestServiceServer + MockMvc);
- getTransaction maps CS fields + `statusHistory`;
- detail carries `failureReason`/`statusLabel`/`declineReasonText`/`statusHistory`;
- caller with `txn.view` (no `ops:operate`) CAN search + read detail, CANNOT resolve (403);
- fail-closed: no-permission read → 403.
6 existing `AdminDashboardController` tests updated for the new `OpsRbacGuard` ctor arg (dev gate-off).

## Remaining (≤3)
1. transaction-mgmt must actually emit `userRef`/`reference` filters + the 4 CS response fields (built in parallel) — BFF is forward-compatible; verify field names on integration.
2. `merchantName` still null (wallet response carries it, not the txn) — pre-existing, out of scope.
3. Build env: two gradle builds over one worktree corrupt each other's `build/`; run serially with an isolated `--gradle-user-home`.
