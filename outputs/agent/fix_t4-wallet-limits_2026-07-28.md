> 작업: T4-2 wallet limits enforcement / 출처: agent

# T4-2 — Wallet `/v1/pay` limit enforcement

**Scope touched:** `services/payment-executor` only. `services/prefunding` NOT modified (its
`chargeCumulative` / `reverseCumulative` already do exactly what was needed, race-free under the
per-partner row lock). No ops-partner-bff, config-registry, compose, Helm or apps changes.

---

## 1. What was actually broken (correction to the register wording)

The register/audit said "the three wallet services call `prefundingClient.deduct(...)` directly".
Verified state before the fix:

| Wallet entry point | Prefunding | Limits |
|---|---|---|
| `SendmnPaymentService` | `deduct(...)` (real, USD-converted) | none |
| `GmeremitPaymentService` | **none at all** (no `PrefundingClient` field) | none |
| `NepalPaymentService` | none | none |
| `FailoverPaymentRouter` | none | none |

Two further findings:

* **`NepalPaymentService` is dead code** — zero callers repo-wide. A Fonepay/NepalPay scan reaches
  `FailoverPaymentRouter` (the controller routes every known non-ZeroPay network there). So the
  router, not the service, is the enforcement point that actually matters for Nepal. Both are gated.
* **No `partner_limits` rows are seeded anywhere** (V020/V034 define the table; there is no INSERT in
  any migration). So today's default behaviour is "unconstrained", and the gate changes nothing until
  an operator configures caps via the wizard — which is why this landed without breaking the sandbox.

---

## 2. Enforcement points added

New in `payment-executor/domain`:

* **`WalletLimitGate`** (`@Component`) — the single rule for the wallet path:
  1. per-transaction min/max USD via the existing `TransactionLimitPolicy` (pure, no side effect);
  2. cumulative daily/monthly/annual USD **and** the V034 daily transaction-count velocity cap via
     `PrefundingClient.chargeCumulative(...)` — i.e. the wallet path now rides the *same* cap-aware,
     row-locked path `POST /v1/payments/authorize` uses, instead of the bare `deduct`.
  Returns a `LimitCharge` handle; `gate.reverse(charge)` gives the cap back on any decline so a
  payment that never completed does not permanently consume cap.
* **`UsdAmountBasis`** — the one USD-conversion source (see §3).
* **`WalletPartnerRef`** — the limit subject (partner *code* for `resolveLimits`, numeric *id* for the
  cumulative ledger). `GMEREMIT`=1, `SENDMN`=2, matching the controller's existing constants.
* **`LimitCheckUnavailableException`** — fail-closed refusal, code `LIMIT_CHECK_UNAVAILABLE`.

Wired into every wallet money path, always **after** merchant validation and **before** any
irreversible step:

| Path | Gate call | Amount basis |
|---|---|---|
| `SendmnPaymentService` | `enforceUsd(...)` before `deduct` | `chargedUsd` — the exact figure the deduct moves |
| `GmeremitPaymentService` | `enforce(..., "KRW")` before the ZeroPay submit | `amountKrw / krwPerUsd` |
| `FailoverPaymentRouter` | `enforce(..., payCurrency)` once per payment, before the candidate loop | resolved pay currency (NPR/…) |
| `NepalPaymentService` | `enforce(..., "NPR")` before the adapter submit | NPR via live USD/NPR |

`FailoverPaymentRouter` keys the cumulative charge on **one** payment-level reference
(`FO-LIMIT-<uuid>`), not the per-hop candidate reference, so a failover cannot double-charge the cap.

---

## 3. Rate-basis decision

**One source, and it is the money source.** `UsdAmountBasis.krwPerUsd()` *is* the routine
`SendmnPaymentService.fetchKrwPerUsd()` used for its prefunding deduction — live `USD/KRW` from the
rate provider with the conservative `1350` fallback — moved out of the service so the deduction and
the limit check cannot drift onto different rates. `SendmnPaymentService.KRW_PER_USD` now *is*
`UsdAmountBasis.KRW_PER_USD_FALLBACK`, and the service delegates. No second rate source was added.

* SENDMN never converts for the gate at all: it passes its already-computed `chargedUsd`, so the cap
  basis is bit-for-bit the money basis.
* GMEREMIT (domestic, moves no float, previously had no rate client) uses the same KRW basis.
* Non-KRW corridors (NPR/MNT) use the live `USD/<ccy>` rate. There is **no blessed fallback constant**
  for them, so an outage yields *no basis* → fail closed (§5), never a guessed rate.

---

## 4. LOCAL-partner decision

`chargeCumulative` was inside `if (partnerType == OVERSEAS)` in `PaymentOrchestrator.authorizeMpm`.
Verified what a LOCAL partner's limits row looks like: **`partner_limits` (V020) has no partner-type
column and no seeded rows** — a LOCAL row is structurally identical to an OVERSEAS one, and the
statutory ceilings hang off `license_type` (the licence), not off the funding model.

Therefore the OVERSEAS gating was an implicit, undocumented "LOCAL partners are uncapped" rule, and
it is removed:

* the **cumulative charge** now runs for every partner type whenever a cap is actually configured;
* the **float reserve stays OVERSEAS-only** — that *is* a genuine funding fact (LOCAL partners hold
  no float), now the only thing the branch decides;
* a LOCAL partner with no caps configured logs an explicit `debug` line ("uncapped by configuration,
  not by partner type") rather than silently skipping a branch;
* the compensating `reverseCumulative` calls in `releaseHold`, `voidAuthorization` and the
  `confirmMpm` scheme-decline path were moved out of their OVERSEAS branches to match — otherwise a
  declined LOCAL authorize would have eaten cap permanently.

---

## 5. Nepal handling (T4-1 is still open)

Nepal's prefunding/FX gap is register item **T4-1** and was left alone: this change moves no float
and applies no FX on the Nepal corridor. What it does do:

* per-txn **and** cumulative/velocity checks now run on the live Nepal path
  (`FailoverPaymentRouter`) and on the unused `NepalPaymentService`, converting the NPR amount at the
  live `USD/NPR` rate;
* **fail closed, explicitly**: if the partner has limits configured and the USD/NPR rate is
  unavailable, the payment is refused with `LIMIT_CHECK_UNAVAILABLE` (503, retryable) rather than
  proceeding unlimited. Test `failover_failsClosedWithoutUsdBasis` pins this.

Wiring an actual Nepal prefunding deduction remains out of scope (T4-1).

---

## 6. Rejection shape (clean + observable)

A breach throws the **same exceptions the authorize path throws**, so it produces the same
`ApiError` body via `PaymentExceptionHandler`:

* `TransactionLimitExceededException` → 422 `TRANSACTION_LIMIT_EXCEEDED`, `retryable=false`
* `CumulativeLimitExceededException` → 422 `CUMULATIVE_LIMIT_EXCEEDED`, `retryable=false`
* `LimitCheckUnavailableException` → **new** 503 `LIMIT_CHECK_UNAVAILABLE`, `retryable=true`
  (string-ctor `ApiError`, the frozen-lib-errors pattern already used by `OperationalGateException`
  and `SchemeOperationNotSupportedException`)

On every refusal: no scheme call, no float movement, a `FAILED` `ExecutionAttemptEntity` persisted the
way other declines are, and the `DeclineSpikeMonitor` is fed (the controller records the decline
before rethrowing) so a burst of cap rejections raises the same ops alert a scheme-decline burst does.

---

## 7. Tests

`:services:payment-executor:test` → **BUILD SUCCESSFUL**, `:check` (incl. `portabilityGuard`) green.
234 tests, 0 failures.

New `domain/WalletLimitEnforcementTest` (18 tests, USD basis 1 USD = 1350 KRW):

* **SENDMN** — per-txn max exceeded → declined, no scheme call, no prefund movement, FAILED attempt;
  daily cap exceeded → declined; velocity cap reaches prefunding and a breach declines; within limits
  → APPROVED with `chargeCumulative` and `deduct` on the *identical* USD figure (`7.77777778`); no
  limits configured → cumulative ledger never touched; scheme decline → `reverseCumulative` called.
* **GMEREMIT** — per-txn max / daily cap / velocity → declined, no scheme call; within limits →
  APPROVED with the USD basis `1350000/1350 = 1000.00000000`; **the 5,000 / 50,000 USD
  `SOAEK_HAEOEMONG` regime honoured on the wallet path** (6,000 USD-equivalent refused, within-ceiling
  payment charged against the 50,000 annual cap); scheme decline → cap returned.
* **Nepal** — failover router per-txn max / daily cap → declined, no scheme call; within limits →
  APPROVED (NPR pass-through unchanged); **fail-closed** without a USD/NPR rate; and the same two for
  `NepalPaymentService` directly.

Also new:

* `PaymentOrchestratorTest.authorize_localPartner_cumulativeCapEnforced` — a **LOCAL** partner's daily
  cap is evaluated (`CHARGE_CUM` present) with **no float reserve**, and the orphan PENDING txn is
  failed.
* `WalletPayControllerTest.walletPay_perTxnLimitBreach_structuredError` — `/v1/pay` answers 422
  `TRANSACTION_LIMIT_EXCEEDED` with `retryable=false` (ApiError shape, not a decline body).

Existing tests kept green; only two `WalletPayControllerTest` failover stubs needed the new
`WalletPartnerRef` argument (plus an assertion that the subject carries the wallet partner's code).
`prefunding` untouched, so its suite was not re-run.

---

## 8. Residual gaps

1. **`resolveLimits` is still fail-SOFT** — no limits row (404) or config-registry unreachable ⇒
   unconstrained. This is the pre-existing documented contract/risk window, deliberately unchanged
   here (changing it would refuse every payment the moment config-registry blinks). A strict
   fail-closed mode remains a follow-up. Note the asymmetry: once limits *are* resolved, an inability
   to evaluate them fails closed.
2. **A capped partner must have a `partner_balance` row.** `PrefundingService.chargeCumulative` takes
   `lockOrThrow(partnerId)`, so a partner with caps configured but no prefunding balance row (a
   plausible LOCAL/domestic setup) will get `LIMIT_CHECK_UNAVAILABLE` (503) instead of a payment.
   Deployment prerequisite, or decouple the cumulative-usage ledger from `partner_balance` (prefunding
   change, out of scope here).
3. **Idempotency-key handling on a limit refusal.** The refusal propagates as a `RuntimeException`, so
   `payIdempotent` releases the claim rather than recording a replayable 422. A retry with the same key
   re-runs the gate and re-declines deterministically (nothing was moved), so it is safe but not
   replay-optimal. Would be tidied by making the wrapper treat the limit family as a recordable
   business outcome.
4. **CPM (`executeCpm`) still has no limit gate** — pre-existing and unchanged: `CpmPaymentCommand`
   carries only the numeric `partnerId` while `resolveLimits` keys on partner *code*. Threading
   `partnerCode` into the CPM command is the enabling follow-up (the existing TODO at
   `PaymentOrchestrator.executeCpm` still stands).
5. **Wallet partner identities are sandbox constants** (`GMEREMIT`=1, `SENDMN`=2, other aliases hashed)
   rather than config-registry lookups — inherited from `WalletPayController`, now centralised in
   `WalletPartnerRef` instead of duplicated.
6. **`NepalPaymentService` remains dead code.** Gated for correctness, but deleting it (or routing to
   it) is a separate decision tied to T4-1.
7. Nepal/failover corridors book no revenue and move no float, so a cap consumed by a *timeout* whose
   outcome later resolves to "not paid" is only reversed on the best-effort paths described above — the
   fail-safe direction (cap stays consumed) rather than over-permissive.
