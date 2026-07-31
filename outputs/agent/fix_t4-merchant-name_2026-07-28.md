> 작업: T4-4 merchant name / 출처: agent

# T4-4 — Receipts never show a merchant name

**Status: CLOSED.** Branch `feat/exec-gap-closure-2026-07-28`. Not pushed.

## What was actually wrong

Not "we don't know who the merchant is". Every corridor knew, at payment time. The name simply had
nowhere to live: it rode the synchronous `WalletPaymentResponse` back to the wallet and was gone by
the time anything read the transaction again.

Concretely, four separate places each independently decided the answer was `null`:

| Site | Before |
|---|---|
| `transaction-mgmt` schema | no `merchant_name` column at all (only `merchant_id`, `merchant_fee_rate`) |
| `TransactionResponse.java:172` | `null, // merchantName — TODO: from scheme-adapter` |
| `AdminDashboardController.buildDetail` | `null, // merchantName — not persisted on the txn yet` |
| `PartnerPortalController.buildDetail` | bare `null` (mirrors Admin) |

So `partner-portal-ui/.../[txnId]/page.jsx` rendered `merchantName ?? '—'` → an em dash on every
transaction that ever existed, and admin-ui's detail page had no merchant field at all.

Worse, the name *was* being resolved and thrown away in two places:
`SendmnRestSchemeClient.submitMpm` decoded SendMN's `MERCHANT_NAME` at verify-qr and dropped it
before Confirm; `NepalPaymentService` carried `null, // merchantName resolved by the adapter; not
surfaced here yet`.

## Fix

### 1. Persist it (transaction-mgmt)

- `V012__add_merchant_name.sql` — `merchant_name VARCHAR(200)` (matches qr-service's columns, the
  upstream source, so a parsed name cannot be truncated on the way in). **No back-fill, no DEFAULT**
  — see "Honesty" below.
- `TransactionEntity.merchantName` + `Transaction.applyMerchantName()` / `merchantName()`. A
  creation-time snapshot replayed on rehydration and NOT bumping `updatedAt`, exactly like
  `userRef` (V011).
- `CreateTransactionRequest.merchantName` (JSON) → `TransactionService.createFromPaymentExecutor`
  overload → `TransactionController.doCreate`. Blank normalises to null.
- `TransactionResponse.from` returns `txn.merchantName()`.

### 2. Thread it from where it is known (payment-executor)

New field on `TransactionClient.CreateRequest`, whose JSON name matches transaction-mgmt's record
exactly (Jackson binds by name; a typo here would POST null and silently re-open the gap).

| Corridor | Source of the name |
|---|---|
| GMEREMIT / ZeroPay domestic | `qrClient.resolve()` → merchant-qr-data (already in hand at step 1) |
| Orchestrated MPM (`authorizeMpm`) | same, persisted at AUTHORIZE — the earliest moment it is known, so the txn carries it even if confirm never happens |
| **SENDMN** | the adapter's verify-qr `MERCHANT_NAME`, now carried on `MpmSubmitResponse.merchantName` instead of being discarded. It **outranks** the hub's merchant-qr-data row: it is the name the SCHEME will show on the Mongolian side, whereas our row is a cache of the Korean-side registry. |
| **NEPAL** | new `SchemeClient.resolveMerchantName(schemeId, qrPayload)` → `POST /internal/scheme/nepal/decode`. Needed because this corridor performs **no hub-side merchant lookup whatsoever** (the adapter resolves the receiver from the QR) and `/submit` returns `{schemeTxnRef,status,amountPaisa}` only. |
| Failover router | whatever the winning scheme reported on submit (SendMN yes, ZeroPay no) |
| CPM | genuinely nothing — see below |

`resolveMerchantName` is routed by `SchemeClientRouter` and guarded by `ResilientSchemeClient` on the
scheme's own breaker/bulkhead. Three deliberate constraints on it:

1. **Contractually non-throwing.** A display-only lookup must never fail or reverse a payment.
2. **Called AFTER the approval decision**, so it is never in front of the money movement.
3. In `ResilientSchemeClient` a breaker short-circuit is *swallowed to null* rather than propagated —
   on every other method the guard translates OPEN into `SchemeTimeoutException`, which means "fail
   over". Failing a payment over because a NAME lookup was unavailable would be a money-path
   decision taken for a cosmetic reason.

### 3. Surface it (ops-partner-bff + SPAs)

`TransactionSummary.merchantName` + `WireTxn.merchantName` → both `buildDetail`s pass
`summary.merchantName()`. Because the Admin search route is a pass-through of the same summary, the
transactions search page and its CSV export (which already listed `merchantName` as a column) start
carrying real values with no JS change. partner-portal-ui already rendered the field. admin-ui's
detail page gained **Merchant** and **Merchant ID** fields — it previously showed neither.

## Honesty rule — enforced in code, not by convention

The name on a receipt is a factual claim about who was paid, so the only acceptable values are the
real name or nothing. Three specific fabrications were available and all are now blocked:

- **The lenient / dev-synth placeholder.** When merchant-qr-data is unreachable, SENDMN (lenient) and
  GMEREMIT (dev-synth) synthesise `MerchantView("UNKNOWN", "Unknown Merchant", …)` so the payment can
  proceed. That is a statement about *our lookup*, not about the merchant. New
  `MerchantNames.realOrNull(...)` refuses it (plus blanks, `"unknown"`, `"null"`), so it is never
  persisted — a placeholder stored as a name is indistinguishable from a merchant genuinely called
  "Unknown Merchant". The value still reaches the *caller* on the wallet response, where it usefully
  explains why the merchant is unnamed.
- **The merchant id as a stand-in.** `PaymentOrchestrator.executeCpm` was passing `cmd.merchantId()`
  into the `merchantName` slot of `PaymentResult` — a terminal identifier rendered under a "merchant
  name" label, which also made this gap look closed. Now `null`; CPM has no QR decode and no merchant
  lookup, so the name is genuinely unknown there.
- **Back-filling history.** `V012` adds the column and writes nothing. Rows created before it never
  captured a name, so there is nothing to write, and guessing one would forge a receipt value for a
  payment nobody can re-verify.

`MerchantNames` only refuses the *exact* placeholder — a real shop called "Unknown Merchant Coffee"
survives (tested).

## Tests (27 new, all green)

- `transaction-mgmt/MerchantNamePersistenceIT` (3) — round-trip; null preserved through
  save → rehydrate → `TransactionResponse`; **a raw-SQL INSERT without `merchant_name`** (the exact
  shape of every pre-V012 row) reads back null.
- `transaction-mgmt/MerchantNameContractIT` (3) — HTTP-level, deliberately: the field crosses a
  service boundary by JSON name, so a rename binding to null is the failure mode worth defending.
  Covers present / omitted / whitespace.
- `payment-executor/MerchantNamePersistedTest` (10) — asserts on the captured
  `TransactionClient.CreateRequest` (the exact hop where the name used to be dropped) for all three
  corridors, each paired with its null case; plus the `MerchantNames` guard.
- `ops-partner-bff/MerchantNameDetailTest` (4) — **both** builders, name present and absent. They are
  separate methods that must stay in step, so fixing one and leaving the other is how this
  half-regresses.
- `admin-ui .../[txnId]/__tests__/page.test.jsx` (2) — renders the name; renders `—` and does **not**
  duplicate the id when the name is absent.

Verified: `:services:transaction-mgmt:test :services:payment-executor:test
:services:ops-partner-bff:test` → BUILD SUCCESSFUL; `gradlew testClasses` → BUILD SUCCESSFUL
repo-wide; `npx next build` in admin-ui clean; admin-ui vitest run from a real **copy** of the tree
(the `'+'` path bug — 14/14 in `src/app/transactions`).

## Residual / not done

- The Nepal decode is one extra adapter round-trip per **approved** payment. Best-effort and
  post-approval, but it is added latency on the wallet response. If the adapter ever returns the name
  on `/submit`, drop the hop and use `MpmSubmitResponse.merchantName` like SENDMN.
- `merchant_name` is **not indexed** — there is no search-by-merchant-name surface yet. Add an index
  with the feature, not before.
- The GMEREMIT/ZeroPay name is only as fresh as the `merchant-qr-data` row; a renamed store shows its
  old name until that row syncs. Snapshot-at-payment-time is the correct behaviour for a receipt
  (it records what was true then), but it is worth stating.
- CPM receipts still show no merchant name. Closing that needs a merchant lookup on the CPM path,
  which is a product decision (whose merchant id is authoritative there), not a plumbing change.
- Nothing was verified against a live scheme; SENDMN remains externally gated (T4-6).
