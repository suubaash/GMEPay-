> 작업: T4-1 Nepal money path / 출처: agent

# T4-1 — Nepal corridor: real FX / fee / prefunding / revenue + a real signer

Branch `feat/exec-gap-closure-2026-07-28`. Touched only `services/payment-executor`,
`services/scheme-adapter-nepal`, `docker-compose.yml` (additive), `Documentation/GAP_REGISTER.md`.

> **Commit note.** My `docker-compose.yml` and `Documentation/GAP_REGISTER.md` edits were swept into
> another agent's commit `425f164` ("fix(security): arm the internal-auth gate on notification-webhook")
> while they were on disk — that commit staged both shared files wholesale. The content is correct and
> present in HEAD; it is simply attributed to the wrong commit. Everything else is in `06a2396`.

---

## 1. What was broken

`NepalPaymentService.java:36-43` (non-test) documented the defect itself: *"the wallet amount is
treated as NPR and passed straight through… No FX is applied here. TODO(fx)… the wallet-labeled KRW
must not be sent as NPR in production."* Concretely, on the live path:

| element | before | after |
|---|---|---|
| FX KRW→NPR | none (pass-through, ~100× mispricing) | `offerRate = liveMid(KRW/NPR) × (1 − configuredMargin)` |
| service fee | none | configured fee, `chargedKrw = amountKrw + feeKrw` |
| prefunding | never touched | `chargedUsd` deducted once, reversed on decline |
| revenue | never booked | `postRevenueCapture` (FX margin + service charge) |
| transaction values | `StatusPatch` with null margins | real `payoutMarginUsd` / `collectionUsd` |
| signer | `StubNepalSigner` → constant `c3R1Yi1zaWduYXR1cmU=` | `RsaNepalSigner`, RSA-2048/PKCS#1/SHA-256 |
| unpriceable corridor | executed anyway, mispriced | refuses 503, no float moved, no scheme call |

---

## 2. Structure built (mirrors SENDMN step for step)

`NepalPaymentService` is now the corridor's money path:

1. **Price** — `NepalCorridorPricing.resolveRates(partnerCode)`: live KRW/NPR mid, live USD/KRW,
   configured margin → offer rate. Refuses if anything is missing.
2. **FX** — KRW-quoted request derives the NPR payout (`amountKrw × offerRate`, HALF_UP to paisa);
   NPR-quoted request derives the KRW collection (`payoutNpr ÷ offerRate`, HALF_UP to whole KRW).
   Any other amount currency is **rejected**, not reinterpreted. There is no pass-through mode left.
3. **Fee** — `resolveFee(partnerCode, amountUsd, krwPerUsd)`; `chargedKrw = amountKrw + feeKrw`.
4. **T4-2 gate** — `limitGate.enforceUsd(partner, ref, chargedUsd)`. `enforceUsd`, not `enforce`, so
   the cap basis is bit-for-bit the figure the float debit moves (stronger than the pre-T4-1 code,
   which converted a pass-through NPR amount at USD/NPR).
5. **Prefunding** — `deduct(partnerId, partnerTxnRef, chargedUsd)` exactly once. Reversed (plus the
   cumulative cap) on scheme decline, definitive non-APPROVED status, or insufficient float. **Kept**
   on `PENDING`; a transport failure runs the idempotent `lookupStatus` probe (ADR-016 §4) and only
   returns the float on `REJECTED`/`NOT_FOUND` — a payment that may have landed is never reversed.
6. **Submit** — the real NPR payout to `schemeId=NEPAL` (the adapter converts to paisa).
7. **Transaction** — `createPending` records both legs (NPR payout / KRW collection); the APPROVED
   `commitStatus` carries `prefundDeductedUsd`, `collectionMarginUsd=0`, `payoutMarginUsd` (the real
   KRW→NPR margin) and `collectionUsd` — no nulls.
8. **Revenue** — `postRevenueCapture(txnRef, partnerId, schemeId=8, revenueDate, 0, fxMarginUsd,
   feeKrw, "KRW", 0)`, **not** `postRoundingResidual`; failures go to `revenue_posting_failures`.

## 3. Fate of the dead `NepalPaymentService` — kept as the single implementation

**Decision: keep it and make the router delegate to it. Not deleted.**

Why not delete and inline into `FailoverPaymentRouter`: the router is a *dispatcher* — classify,
resolve candidates, submit `amount` in `currency`, fail over on technical errors. Pricing a corridor
is not a generic dispatcher concern (the rate, margin and fee are per-corridor commercial terms), and
inlining it would put an `if (scheme == NEPAL)` money path inside the failover loop. Walking the loop
is exactly *how* the KRW arrived labelled NPR.

So `FailoverPaymentRouter.pay(...)` now, immediately after candidate resolution and before its own
limit gate, delegates any Nepal candidate to `NepalPaymentService.pay(...)` — the same shape as
`WalletPayController` dispatching `partner=SENDMN` to `SendmnPaymentService`. Consequences:

- The former dead class is the **one** Nepal money path; there are no two divergent paths.
- The limit gate still fires **exactly once** per payment (the delegate runs it on its `chargedUsd`).
- **Nepal does not fail over.** One scheme edge; a "failover" would pay a different corridor with
  Nepal's pricing already applied. Extra candidates are logged and ignored, never walked.
- **No money path wired ⇒ the router REFUSES** (`CorridorPricingUnavailableException`). There is
  deliberately no fallback to the old pass-through.

## 4. Fail-closed, no invented pricing

Nothing about the price is defaulted in code. `NepalCorridorPricing` reads, in order:

1. **config-registry commercial terms (preferred — it already models exactly these fields):**
   - margin ← `GET /v1/partners/{code}/fx-config` → `marginBps` (`partner_fx_config`, V019)
   - fee ← `GET /v1/partners/{code}/fee-schedules/effective?schemeId=NEPAL&direction=OVERSEAS&amountUsd=…`
     → `serviceFeeUsd` (`partner_fee_schedule`, V018: fixed + bps + volume tiers, most-specific match)

   New client methods `PartnerConfigClient.resolveFxConfig` / `.resolveServiceFeeUsd` +
   `RestPartnerConfigClient` implementations. **No config-registry code was changed** — both endpoints
   already existed. Both clients stay fail-soft (empty on 404/unreachable); the *decision* to refuse
   lives in the corridor, preserving the "a client never fails a payment by itself" contract.
2. **Module config override:** `gmepay.payment.nepal.fx-margin` (decimal fraction) and
   `gmepay.payment.nepal.service-fee-krw`. **Both unset by default**, so an unconfigured deployment
   fails closed; they exist so a local/sim or pre-onboarding environment can be made to transact by
   *configuration*, not by a hardcoded default.

Refusals (`CorridorPricingUnavailableException` → HTTP 503, stable code on the exception because
lib-errors is frozen):

| cause | code | retryable |
|---|---|---|
| margin / fee / float ledger not configured, or margin out of `[0,1)` | `CORRIDOR_PRICING_NOT_CONFIGURED` | false |
| live KRW/NPR or USD/KRW rate unavailable | `CORRIDOR_RATE_UNAVAILABLE` | true |

Every refusal happens before any side effect: no float moved, no scheme call, a FAILED attempt row
persisted (same as other declines). This mirrors how T4-2 made Nepal fail closed at 503.

**The 1350 KRW/USD fallback is not used to price.** `UsdAmountBasis.KRW_PER_USD_FALLBACK` exists so a
regulatory *cap* can still be evaluated during a rate outage — a conservative direction for a control.
Using it to sell FX is the opposite, and is the smell registered as CFO#11. Nepal fetches USD/KRW
itself and fails closed. **SENDMN's own hardcoded `FEE_KRW=500` / 2% margin / 1350 fallback were left
untouched** — they are CFO#11's subject, and none of them was copied to Nepal.

## 5. Signer — `RsaNepalSigner`

`StubNepalSigner` + `StubNepalSignerTest` deleted. The new signer injects the `nonce`,
base64-encodes the JSON into `data`, and signs the base64 **text** with `SHA256withRSA` (RSA /
PKCS#1 v1.5 / SHA-256, minimum 2048-bit) per `API-DOCS/issuance-extension.txt`.

- Key from `gmepay.scheme.nepal.signing.private-key` (PKCS#8 PEM or bare base64 DER, e.g. env
  `GMEPAY_SCHEME_NEPAL_SIGNING_PRIVATE_KEY`) or `…private-key-path` (mounted secret file). Validated
  at load time — algorithm, PKCS#8 encoding, ≥2048 bits — so a bad key fails at startup, not at the
  first payment. **No key is committed and nothing key-related is logged.**
- **Fails closed:** with no key the bean still starts (so `/decode` + health probes work) but every
  `sign()` throws `503 SCHEME_UNAVAILABLE` naming the missing property. No placeholder signature is
  ever emitted.
- **Local/sim keeps working by configuration:** `…signing.mode=EPHEMERAL_DEV` is an explicit opt-in
  that mints a throwaway RSA-2048 keypair at startup with a loud warning. `sim-nepal-qr` soft-logs
  signatures, so the *real* signing path executes locally instead of being short-circuited by a
  constant. `docker-compose.yml` sets it for the single-host stack (overridable via `.env`);
  Helm/production leave it unset. **No `sim-nepal-qr` change was needed.**

## 6. Tests — 33 added/rewritten, all green

`NepalPaymentServiceTest` (24): FX applied / never pass-through (explicitly asserts the wire amount
≠ the KRW amount); offer rate = mid − configured margin; NPR-quoted derivation; fee applied;
prefunding deducted exactly once on the fee-inclusive USD figure; reversed on scheme decline (same
reference) and on non-APPROVED; **kept** on PENDING; insufficient float declines before the scheme;
revenue booked to the real FX-margin/service-charge accounts with `postRoundingResidual` asserted
**never** called; commit carries real margins; both legs recorded; config-registry-sourced pricing
(250 bps + 0.5 USD fee) applied; six fail-closed refusals (no margin / no fee / no KRW-NPR rate /
no USD-KRW rate / nonsense margin / no float ledger) each asserting no scheme call + no float moved;
and a nested T4-2 block (per-txn breach, cap charged on the real `chargedUsd`, cumulative breach,
decline releases both cap and float).

`FailoverPaymentRouterTest` +2: Nepal delegation (delegate called, `submitMpm` never), and refusal
when no money path is wired. `WalletLimitEnforcementTest` Nepal block rewritten to drive the real
production chain (`/v1/pay` → router → corridor → gate) on KRW amounts. The generic
failover-mechanics candidate in `FailoverPaymentRouterTest` and `ResilientFailoverIntegrationTest`
moved off `NEPAL` to `khqr` — Nepal is delegated now, so it can no longer stand in for "some
cross-border scheme"; the breaker/failover contracts under test are scheme-agnostic.

```
gradlew.bat :services:payment-executor:test :services:scheme-adapter-nepal:test   → BUILD SUCCESSFUL
gradlew.bat testClasses                                                           → BUILD SUCCESSFUL
```
`check_internal_auth_wiring.py` 67/70 (the 3 mismatches are pre-existing `notification-webhook`
items owned by another agent), `check_monitoring_wiring.py` 36/36,
`docker/keycloak/check-topology.mjs` 101/101.

---

## 7. ⚠️ VALUES AN OWNER MUST SUPPLY BEFORE THE CORRIDOR CAN TRANSACT

The corridor is **structurally complete and currently refuses every payment**. Two business decisions
are missing, and both are deliberately un-defaulted:

| # | value | unit | where it goes (preferred) | fallback location | symptom while missing |
|---|---|---|---|---|---|
| 1 | **KRW→NPR FX margin** | basis points | config-registry `partner_fx_config.margin_bps` for the wallet partner (step-6 commercial panel, `PATCH /v1/partners/draft/{code}/step-6-commercial`) | `gmepay.payment.nepal.fx-margin` (decimal fraction) | 503 `CORRIDOR_PRICING_NOT_CONFIGURED` — "the FX margin is not configured" |
| 2 | **Nepal service fee** | USD (fixed + bps + tiers) | config-registry `partner_fee_schedule` row for `schemeId=NEPAL`, `direction=OVERSEAS` | `gmepay.payment.nepal.service-fee-krw` (flat KRW) | 503 `CORRIDOR_PRICING_NOT_CONFIGURED` — "the service fee is not configured" |

Two further inputs are **operational**, not pricing decisions, but must exist:

| # | value | note |
|---|---|---|
| 3 | **`reference_rate_source`** on `partner_fx_config` | audit label only (`SEOUL_FX_BROKER` / `PARTNER_PROVIDED` / `MID_MARKET`); it does not alter arithmetic. The rate itself always comes live from the rate provider — **a live KRW/NPR pair and a live USD/KRW pair must be served**, or the corridor refuses with `CORRIDOR_RATE_UNAVAILABLE`. |
| 4 | **Khalti-issued RSA private key** | PKCS#8, ≥2048-bit, into `GMEPAY_SCHEME_NEPAL_SIGNING_PRIVATE_KEY(_PATH)` from the secret store. Without it signed `pay`/`status` refuse with 503. `EPHEMERAL_DEV` covers local/sim only. |
| 5 | **`partner_balance` row + funded USD float** for the wallet partner | the corridor debits USD prefunding per payment; an unfunded partner declines `INSUFFICIENT_PREFUNDING` (and a capped partner needs the row for the T4-2 cumulative ledger — pre-existing T4-2 residual). |

**Sales position until #1 and #2 are configured: the Nepal corridor is NOT sellable.** It refuses
cleanly at 503 with a self-describing code rather than mispricing — which is T4-1's stated acceptable
outcome.

## 8. Still open on this corridor (out of scope here)

- **`merchantName` is still null** on Nepal receipts — that is T4-4, and needs a
  `transaction-mgmt` column plus adapter carry-through.
- **No scheme-side refund** for Nepal (single-shot `pay`); `SCHEME_OPERATION_UNSUPPORTED` per T2-7/P8.
  A reversal is a manual/ops process.
- **`SENDMN`'s hardcoded pricing** (`FEE_KRW=500`, 2% margin, 1350 fallback) is untouched — CFO#11.
- **Deferred `commission split`** — Nepal posts `feeSharePct=0`; the two-sided split engine wiring is
  task #98, gated on merchant-fee sync.
- **Not requested from other agents' modules.** Nothing was needed in `services/prefunding`,
  `services/settlement-reconciliation`, `services/notification-webhook`, `services/ops-partner-bff`
  or `apps/admin-ui`: the corridor uses `PrefundingClient.deduct/reverse/chargeCumulative` and the
  config-registry endpoints exactly as they already exist. Once pricing is configured, an admin-ui
  surface for the Nepal fee/margin panel would be nice-to-have but is not required — the existing
  step-6 commercial-terms UI already edits both tables.
