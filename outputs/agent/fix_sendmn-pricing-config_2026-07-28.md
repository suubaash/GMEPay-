> 작업: SENDMN pricing externalisation / 출처: agent

# CFO#11 — SENDMN's price moves out of Java and into config-registry, without moving the price

Branch `feat/exec-gap-closure-2026-07-28`. Touched only `services/payment-executor` and
`Documentation/GAP_REGISTER.md`. No config-registry code was changed. No SPA was changed.

---

## 1. The defect, precisely

`SendmnPaymentService` carried its own commercial terms:

| element | before |
|---|---|
| service fee | `static final BigDecimal FEE_KRW = new BigDecimal("500")` |
| FX margin | `@Value("${gmepay.payment.sendmn.fx-margin:0.02}")` — **and** `application.properties:135` re-stated `=0.02` |
| USD basis | `UsdAmountBasis.krwPerUsd(rateClient)` → live USD/KRW, else the `1350` constant |

Meanwhile `config-registry` owns commercial terms for every partner — `partner_fx_config.margin_bps`
(V019), `partner_fee_schedule` (V018, fixed + bps + volume tiers), both editable in the partner step-6
commercial panel, both already exposed on `GET /v1/partners/{code}/fx-config` and
`GET /v1/partners/{code}/fee-schedules/effective`.

So the corridor's price existed in two places and **the code silently won**. The sharp edge was not the
literal `500`; it was that the property was *explicitly set in `application.properties`*, so a margin an
owner entered in the registry, saw saved, and saw rendered back in the UI would have changed **nothing**
about what a customer was charged — and there was no signal anywhere that this was the case.

## 2. Fix: T4-1's mechanism generalised, not a second one invented

`NepalCorridorPricing`'s resolution logic is extracted **verbatim** into a new shared
`CorridorPricing` base. Both corridors are now thin descriptors over it:

```
CorridorPricing (abstract)          ← all resolution logic + Rates / Fee / Provenance
 ├─ NepalCorridorPricing            ← @Component, Nepal's Spec  (unchanged behaviour)
 └─ SendmnCorridorPricing           ← @Component, SENDMN's Spec (new)
```

Resolution order, identical for both: **module-config override → config-registry → corridor policy**.

Nepal's public surface is preserved exactly (`CORRIDOR`, `SCHEME_CODE`, `PAYOUT_CURRENCY`,
`COLLECTION_CURRENCY`, `USD_SCALE`, `resolveRates`, `resolveFee`, its constructors) — the statics stay
declared on the subclass, the records are inherited, and all 24 pre-existing Nepal corridor tests plus
the T4-2 gating block pass **untouched**. `NepalPaymentService` changed only in that
`rates.offerRateNprPerKrw()` is now `rates.offerRate()`.

`application.properties`'s `gmepay.payment.sendmn.fx-margin=0.02` is **deleted**. That line was the
whole defect wearing a config costume: leaving it would have kept the registry unreachable. The keys are
documented in its place as a local/sim escape hatch, unset by default.

## 3. The one point where the two corridors differ, deliberately

`Spec.codeDefaultMarginFraction` / `Spec.codeDefaultFeeKrw`:

| corridor | code defaults | unconfigured behaviour | why |
|---|---|---|---|
| **Nepal** | `null` / `null` | **refuses**, 503 `CORRIDOR_PRICING_NOT_CONFIGURED` | had *no* price at all (KRW went out as NPR); a guessed margin is the same defect as the pass-through |
| **SENDMN** | `0.02` / `500 KRW` | **transacts, at today's price** | has a working price and live corridor traffic; refusing would be an outage, and re-pricing would be a business decision |

**This is a behaviour-preserving refactor.** Neither constant is a price chosen here — each is a record
of a price GME is *already* charging, held until an owner confirms it. Concretely, with nothing configured
(the state of every environment today) SENDMN produces bit-for-bit the same output it did before:
₩500 fee, ₩10,500 charged, 3.43 MNT/KRW offer rate, 34,300 MNT on the wire, 7.77777778 USD deducted,
0.1481 USD booked margin.

Two implementation details exist purely to guarantee that:

- The offer-rate `MathContext` is **per corridor** — SENDMN keeps precision 10, Nepal keeps 12. A shared
  resolver must not shift a live corridor's arithmetic by a digit.
- The convenience test constructor `SendmnPaymentService(..., BigDecimal fxMargin, ...)` survives and
  wraps the margin as a module-config override, so every pre-existing SENDMN test compiles and asserts
  the same numbers against the new path rather than being rewritten to agree with it.

## 4. Not silent any more — three surfaces

1. **A one-shot WARN per term per JVM**, naming the exact place an owner must enter the real value:
   > `SENDMN corridor (KRW→MNT) is pricing on a CODE-DEFAULT FX margin of 0.02 — this value is hardcoded
   > in payment-executor, NOT owner-configured. Enter the real margin in config-registry
   > (GET /v1/partners/SENDMN/fx-config) or, for a local/sim environment, set
   > gmepay.payment.sendmn.fx-margin. Today's effective pricing is preserved until then (CFO#11).`
2. **`CorridorPricing.provenance()`** — a readable record (`marginSource`, `marginOnCodeDefault`,
   `feeSource`, `feeOnCodeDefault`, `usdBasisFallbackSeen`, `ownerActionRequired`). A partially-configured
   corridor still reports `ownerActionRequired=true`. No new metric was added: the monitoring guard's
   contract stays untouched and the domain layer stays free of a `MeterRegistry`.
3. **Every approval logs which terms priced it** — `margin … from …; fee from …; usdBasisFallback=…`, so
   "what did we charge and on whose authority" is answerable from the transaction log.

## 5. The `KRW_PER_USD = 1350` decision — **kept, and made an owner's switch**

This was called out as a separate question and it is. **Decision: keep the fallback as the default.**

It is not a price. It converts `chargedKrw` into the USD figure the prefunding float is debited by and the
T4-2 regulatory cap is evaluated on — a *control* basis, whose conservative direction is precisely why
T4-2 sanctioned it. Failing closed on it, as T4-2/T4-1 made Nepal do, would take a **live** corridor
offline during a rate-provider blip; Nepal could afford that stance because it was not transacting at all.
And the exposure is already **detected rather than ignored**: `UsdAmountBasis` WARNs on every use, and
T2-2's tie-out back-derives `chargedKrw / usdDeducted` per transaction and counts the affected ones in
`corridor_recon_summary.fallback_rate_basis_count`. A silently wrong USD basis is what that work exists to
catch; making the corridor refuse instead would trade a detected variance for an outage.

What changes is that it stops being a property of the code:

- `gmepay.payment.sendmn.usd-basis-strict=true` → SENDMN refuses with 503 `CORRIDOR_RATE_UNAVAILABLE`
  (retryable, no float moved, no scheme call) instead of falling back.
- Default `false` = today's behaviour, so **this branch changes nothing** until an owner chooses.
- `Rates.usdBasisFallbackUsed()` / `provenance().usdBasisFallbackSeen()` report which happened.

Both branches are pinned by tests. Separately: an unavailable **KRW/MNT** rate refuses in *both* modes —
an FX *offer* rate is never computed off a fallback constant on any corridor.

## 6. Tests

`SendmnPricingConfigTest`, 12 tests, all green:

- **the regression pin**: nothing configured → today's exact ₩500 / 2% / 34,300 MNT / ₩10,500 /
  7.77777778 USD deducted / 0.1481 USD booked margin / ₩500 service charge on the revenue capture;
- the unconfigured state is visible (both terms `CODE_DEFAULT`, provenance names the config keys,
  `ownerActionRequired`);
- config-registry `marginBps=250` prices the payment (34,125 MNT, 0.1852 USD margin) and reports as
  registry-sourced; config-registry `serviceFeeUsd=0.5` charges ₩675 / ₩10,675;
- a partially-configured corridor still flags; both configured → no owner action;
- module-config overrides beat the registry (escape hatch intact);
- USD basis: default falls back to 1350 and reports it; a live 1380 is used and reports no fallback;
  `usd-basis-strict=true` refuses with nothing moved; KRW/MNT unavailable refuses in both modes;
- Nepal still fails closed on the *same* resolver with the *same* absent configuration while SENDMN
  transacts — the difference is asserted as intentional, so a future edit cannot quietly align them.

```
gradlew.bat :services:payment-executor:test :services:config-registry:test   → BUILD SUCCESSFUL
gradlew.bat testClasses                                                      → BUILD SUCCESSFUL
```
Guards: `check_internal_auth_wiring.py` 95/95, `check_monitoring_wiring.py` 37/37,
`check_helm_chart_wiring.py` 199/199, `check_gitleaks_config.py` 0/0/0/0,
`check_load_harness_wiring.py` OK, `docker/keycloak/check-topology.mjs` 101/101.

## 7. ⚠️ VALUES AN OWNER MUST CONFIRM, AND WHERE THEY NOW GO

The corridor is **transacting normally**. Nothing here is blocking. But two numbers currently in force
have never been confirmed by anyone with the authority to set a price:

| # | value | in force today | where the owner enters it | escape hatch (local/sim only) |
|---|---|---|---|---|
| 1 | **KRW→MNT FX margin** | **2%** (code default) | config-registry `partner_fx_config.margin_bps` for partner **`SENDMN`** — step-6 commercial panel, `PATCH /v1/partners/draft/SENDMN/step-6-commercial` | `gmepay.payment.sendmn.fx-margin` (decimal fraction) |
| 2 | **SENDMN service fee** | **₩500 flat** (code default) | config-registry `partner_fee_schedule` row for `schemeId=SENDMN`, `direction=OVERSEAS` (fixed + bps + tiers all supported) | `gmepay.payment.sendmn.service-fee-krw` (flat KRW) |

Confirming #1 as `200` bps and #2 as the USD equivalent of ₩500 is a perfectly valid outcome — the point
is that it becomes a recorded decision with an author, and the WARN and `ownerActionRequired` flag go
quiet. Once both are entered the code defaults are unreachable and can be deleted.

A third, independent decision:

| # | decision | default | consequence of flipping |
|---|---|---|---|
| 3 | **USD/KRW basis strictness** — `gmepay.payment.sendmn.usd-basis-strict` | `false` (fall back to 1350) | `true` trades availability for basis accuracy: SENDMN refuses during a USD/KRW outage instead of debiting the float at a stale rate. Choose `true` only if the T2-2 `fallback_rate_basis_count` / `RATE_BASIS_VARIANCE` signal shows the exposure is material. |

Also worth noting: `partner_fx_config.reference_rate_source` is an **audit label only** (it does not alter
arithmetic), and there is no seeded fx-config or fee-schedule row for `SENDMN` today — which is exactly
why this change is behaviour-neutral.

---

## 8. ITEM 2 — the admin-ui reporting residual: **already done, nothing changed**

T5-2's register note listed three consumer follow-ups. All three were verified against the current tree
and all three are closed by later work; **the note was stale and no code needed writing.**

| listed as open | actual state |
|---|---|
| `admin-ui/src/api/reportsApi.js` mocks say `SUBMITTED` | fixtures carry `GENERATED` / `NOT_FILED_CHANNEL_UNAVAILABLE` with a per-row `FIXTURE_NOT_FILED_REASON` and all three lanes reported dark. The only `SUBMITTED` left in `apps/admin-ui/src` outside tests is `filingStatus.js`'s `RETIRED_FILING_STATUSES`, whose purpose is to *reject* it |
| BFF `RestReportingClient` hardcodes `GENERATED` | passes the upstream status through and normalises the retired vocabulary; the hardcode is gone (its javadoc documents the removal) |
| `RegulatoryConfigSummary.hometaxSet` still means "config entered" | `compliance/page.jsx`'s `ConfigBadge` (replacing `SetBadge`) renders it neutral/warning "Configured" — **never green** — with a tooltip stating it does not mean the lane can file |

Supporting checks: `filingStatus.js` grants `color: 'success'` to **only** `TRANSMITTED` and
`ACKNOWLEDGED`, both unreachable today; `FilingChannelBoard` colours a lane success only when
`channelLive`; `app/reports/__tests__/page.test.jsx` asserts `SUBMITTED` cannot appear in the table and
that a stale `SUBMITTED` renders as **"SUBMITTED — not a valid state"**.

No SPA file was modified, so no `npx next build` / vitest run was required. The register's T5-2 entry is
corrected in place to record the verification rather than leaving a stale open item.

## 9. Still open elsewhere (out of scope here)

- **CFO#15** — SENDMN's MNT rounding residual is untouched.
- **T5-2's real gates** remain external: BOK SFTP / `TODO_OI03` (OI-03), NTS mTLS (OI-02), KoFIU endpoint
  + file spec, and the empty `StubKofiuTransactionPort`.
- **Commission split** — SENDMN still posts `feeSharePct=0`; the two-sided engine wiring is task #98.
- **Nepal's own residual** is unchanged: it still refuses every payment until an owner supplies its margin
  and fee (T4-1), which is by design and is *not* the same state SENDMN is in.
