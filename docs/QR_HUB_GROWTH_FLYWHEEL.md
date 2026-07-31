# GMEPay+ — QR Hub Growth Flywheel ("the loop")

> **This is a STRATEGY document — an intended trajectory, not a description of a running
> business.** The starting point it assumes does not exist yet: no corridor has moved real money,
> the ZeroPay real-time path runs against a simulator and is uncertified (T4-3), the Nepal
> corridor **refuses every payment** until an owner supplies its FX margin and service fee (T4-1),
> and SENDMN runs on placeholder credentials (T4-6). Authority on current state:
> `Documentation/GAP_REGISTER.md`.

**Goal:** grow GMEPay+ from a Korea↔Nepal remittance-anchored QR hub into an Alipay+-class
cross-border acceptance network.
**Companion docs:** `MASTER_PLAN.md` (build readiness), `docs/SERVICE_MAP.md` (architecture),
`docs/PARTNER_SETUP_PLAN.md` (onboarding).

---

## 1. Why a loop, not a roadmap

Alipay+ did not scale by signing deals one at a time. It scaled because every new acceptance
market made the platform more valuable to every wallet, and every new wallet made the platform
more valuable to every acceptance market — while the *marginal cost of the next integration kept
falling*. That is the property to engineer. A roadmap ships features; a flywheel compounds.

## 2. The core loop

```
        ┌─────────────────────────────────────────────────────────┐
        │  ① Add ONE acceptance network (one scheme adapter        │
        │     = an entire country's QR merchants)                  │
        └──────────────────────────┬──────────────────────────────┘
                                   ▼
        ② Bigger footprint → easier to sign the NEXT sending
           wallet: one hub integration = the whole footprint
                                   ▼
        ③ More wallets → more cross-border TPV through the hub
                                   ▼
        ④ TPV compounds economics: FX margin + MDR share
           (revenue-ledger 70/30), better liquidity pricing,
           higher prefunding turns
                                   ▼
        ⑤ Reinvest margin into the NEXT adapter + corridor
           incentives ──────────────────────────► back to ①
```

**The flywheel test (apply to every initiative):** does this make the *next* turn cheaper or
faster? Adding a wallet must make the next scheme worth building; adding a scheme must make the
next wallet easier to sign. If an initiative does neither, it is not on the loop.

## 3. The five sub-loops that spin the big one

Each maps to services we already have — the loop is an operating strategy for the existing
architecture, not a new build.

### A. Adapter-factory loop (supply side)
`scheme-adapter-zeropay` + `scheme-adapter-nepal` → after the second adapter is certified
(**no adapter has been certified by any scheme yet** — T4-3, so this loop has not started),
extract a **Scheme Adapter SDK + certification kit** (shared EMVCo/CPM handling from
`qr-service`, code-mapping tables, recon-file harness from `settlement-reconciliation`,
simulator template from `simulators/`). Every adapter built makes the next one cheaper.
**KPI: adapter time-to-live** — target 2 quarters → 1 quarter → 6 weeks. *No baseline exists: no
adapter has ever gone live, so the first value of this KPI is still unmeasured.*

### B. Partner self-service loop (demand side)
`partner-portal-ui` + `kyb-adapter` + `auth-identity` + `gmepay-test-platform`/`simulators` →
a wallet partner should self-serve: sign up → KYB → sandbox keys → pass scripted E2E against
simulators → prefund → live. Alipay+ still onboards wallets with bizdev armies; self-serve
onboarding is how a smaller team out-scales its headcount.
**KPI: partner time-to-first-live-transaction** — target < 30 days.

### C. Capital-efficiency loop (the moat)
`prefunding` + `settlement-reconciliation` + `rate-fx` (USD-pivot) → as corridors multiply,
move from per-corridor prefunding to **multilateral netting**: opposing flows (Korea→Nepal
spend vs Nepal→Korea spend) offset, so each new corridor traps *less* capital per partner, and
smaller wallets can afford to join. Volume → better FX pricing from liquidity providers →
better consumer rate → more volume. This loop is the hardest for competitors to copy.
**KPI: prefunding turn ratio** (TPV / average prefunded balance).

### D. Diaspora cold-start loop (our unfair advantage)
Alipay+ bootstrapped on Chinese outbound tourists. GME's equivalent is the **remittance
diaspora**: workers in Korea already using GME Remit are day-one QR payers, and their families
are day-one QR spend at home-country merchants. Every remittance user is a warm lead for QR;
every QR transaction deepens the remittance relationship. Bundle them (e.g. fee credit on
remittance for QR usage) so each product feeds the other.
**KPI: remittance-user → active-QR-payer conversion; transactions/payer/month.**

### E. Data & value-added loop (later, self-funding)
`transaction-mgmt` + `reporting-compliance` data → merchant-side offers and campaigns targeted
at inbound wallet users (Alipay+'s "marketing solutions"). Raises frequency per user and gives
acquiring schemes a reason beyond MDR to prioritize us.
**Do not start before Turn 3** — it only works on top of real volume.

## 4. Sequencing the turns (honest about readiness)

`MASTER_PLAN.md` puts build-to-launch at ~4%; the loop cannot spin before the money path is
real. Do not let expansion bizdev outrun R-phase execution — but instrument the loop KPIs from
day one so Turn 1 is measured, not guessed.

| Turn | When | What spins |
|---|---|---|
| **0 — build the wheel** | now → Oct 2026 | R0–R3 money path real, ZeroPay/KFTC certified, GME Remit domestic live. Ship loop-KPI dashboard in `admin-ui`. |
| **1 — first rotation** | Oct–Dec 2026 | Overseas wallet partners live on Korea acceptance. Prove the onboarding playbook (loop B); measure time-to-first-transaction. |
| **2 — first two-sided corridor** | 2027 | `scheme-adapter-nepal` live → Korea↔Nepal both directions (loop C netting begins). Replicate acceptance into 2–3 markets where GME already has remittance presence and licenses. Extract the Adapter SDK (loop A) from the second certification. |
| **3 — platformize** | 2028+ | Self-serve partner onboarding at scale, adapter kit for scheme-initiated integrations ("connect your national QR to GMEPay+"), value-added data services (loop E). The loop self-funds. |

## 5. The loop dashboard (single source of truth)

Track per quarter, visible in `admin-ui`:

1. Live sending wallets / acceptance schemes (the two network sides)
2. Acceptance points reachable (sum of merchant counts across live schemes)
3. Cross-border TPV and blended take rate
4. Prefunding turn ratio (loop C health)
5. Adapter time-to-live (loop A health)
6. Partner time-to-first-transaction (loop B health)
7. Monthly active cross-border payers; transactions/payer/month (loops D/E health)

If (1)–(3) grow while (4)–(6) *shrink*, the flywheel is compounding. If (1)–(3) grow while
(4)–(6) stay flat, we are buying growth linearly — that is the Alipay+ gap, not the path to it.
