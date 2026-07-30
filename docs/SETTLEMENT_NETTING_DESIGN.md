# Multilateral Settlement Netting — Design Note (flywheel loop C)

**Status:** design + calculator implemented; wiring gated on the first two-sided corridor (Turn 2).
**Companions:** `docs/QR_HUB_GROWTH_FLYWHEEL.md` (loop C), `docs/QR_HUB_FLYWHEEL_TRACKER.md` (item #6),
`docs/SETTLEMENT_FLOW_SPEC.md` + `Documentation/ADDENDUM-001-settlement-rounding.md` (today's flows),
`docs/MONEY_CONVENTION.md` (USD scale 2, HALF_UP).

## 1. Why netting is the loop-C moat

Today every corridor settles **one-directional gross**: overseas partners prefund USD, the hub
pays the acquiring scheme (ZeroPay) in full, and capital sits trapped per corridor. Once a
corridor runs **both directions** (Korea→Nepal spend and Nepal→Korea spend through the same
counterparties), the two obligations against each counterparty offset — only the *difference*
moves. Each new two-sided corridor then *reduces* the prefund a partner must trap per unit of
TPV, which lets smaller wallets afford to join, which grows TPV — the loop-C turn. The KPI is
the **prefunding turn ratio** on the Flywheel dashboard (`GET /v1/admin/flywheel`).

## 2. Model

- The **hub is the central counterparty**. Every obligation is bilateral: hub ↔ one
  counterparty (a sending partner or an acquiring scheme), pivoted to USD (rate-fx RATE-04).
- Sign convention: positive = hub pays counterparty; negative = counterparty pays hub.
- A netting run collapses a value-date window into **one net position per counterparty**:
  `MultilateralNettingCalculator.calculate(valueDate, obligations)` →
  per-counterparty `Position(gross, net)` + `grossFundingUsd`, `netFundingUsd`,
  `nettingEfficiencyPct` (capital freed; `null` on an empty window — unmeasured, never 100%).

## 3. Phasing

| Phase | Scope | Trigger |
|---|---|---|
| **N0 (now)** | Calculator + tests in `settlement-reconciliation` (`calculator/MultilateralNettingCalculator`); efficiency metric visible in reports as 0% while corridors are one-sided | calculator built + wired as a REPORT only (`applied=false`); nothing funds on a netted basis, and no settlement file has ever been transmitted (T4-5) |
| **N1** | Batch job builds obligations from settled transactions per value date; net positions drive the settlement instruction file; recon asserts `net = Σ signed obligations` per counterparty | first two-sided corridor live (Nepal adapter, Turn 2) |
| **N2** | Netting-aware prefund thresholds: partner low-balance tiers computed on *expected net* outflow, not gross; feeds loop C's prefunding-turn KPI directly | ≥ 2 two-sided corridors |

## 4. Invariants & constraints (the honest part)

1. **Never net across counterparties.** ZeroPay's payable cannot offset NepalPay's receivable —
   that is a scheme-level netting agreement we do not have. The calculator enforces this by
   construction (positions keyed per counterparty).
2. **Netting changes cash movement, never books.** Gross obligations still post to
   `revenue-ledger` double-entry journals line by line; netting only collapses the *settlement
   instruction*. Recon must tie `Σ journal lines = gross` and `instruction = net` per counterparty.
3. **Value-date discipline.** Only obligations sharing a value date net; carrying an obligation
   across windows is a credit decision (prefunding's credit-limit machinery), not a netting one.
4. **Regulatory:** cross-border netting positions may need BOK FX-report treatment (OI-03 gates
   the format) and each corridor's central-bank rules decide whether net or gross must be
   reported. N1 wiring must not start before Compliance signs the per-corridor treatment.
5. **Rounding:** USD scale 2 HALF_UP everywhere; per-counterparty residuals follow
   ADDENDUM-001's partner rounding-mode rules when converting net USD to payout currency.
