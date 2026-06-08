# Specification Addendum 001 — Per-Partner Settlement Rounding

**Status:** Approved · **Date:** 2026-06-08 · Supplements `GMEPay+_Complete_Specification.docx`.
This addendum records a requirement added after the v1.0 specification was issued. It amends the
sections below; treat it as authoritative over the original text where they differ.

## Requirement
Different partners book their settlement liability under different rounding rules (e.g. a partner
rounds **DOWN** to 2dp while GMEPay+ rounds **HALF_UP**). GMEPay+ must book each partner's liability
under **that partner's** rule so partner reconciliation is exact, and post the rounding difference
(residual) to a dedicated ledger account so the internal books stay balanced.

## Amended specification sections
- **RATE-04 §10 (Rounding, Precision & Currency Scale):** the final settlement amount booked to a
  partner is rounded using the partner's configured `settlement_rounding_mode` (not a fixed HALF_UP).
  USD-pool intermediates remain full-precision; the pool-identity tolerance is unchanged.
- **DAT-03 (Data Model):** `partner.settlement_rounding_mode` (enum, default `HALF_UP`); transaction
  gains locked fields `booked_settlement_amount`, `settlement_rounding_mode`, `rounding_residual`.
- **PRD-07 (Admin System §Partner management):** add a "Settlement rounding mode" setting on partner
  onboarding/edit (HALF_UP | HALF_DOWN | HALF_EVEN | DOWN | UP | CEILING | FLOOR), audit-logged.
- **Settlement & Revenue (RATE-04 §12 / revenue-ledger):** add account `REVENUE_ROUNDING`; the residual
  posts as a balanced rounding gain (residual > 0) or loss (residual < 0); zero residual posts nothing.
- **REF-15 (Glossary):** add `settlement_rounding_mode`, `booked_settlement_amount`, `rounding_residual`,
  `REVENUE_ROUNDING`.

## Cross-service contract (MSA)
- **config-registry** owns `partner.settlement_rounding_mode` and exposes it via `GET /v1/partners/{id}`.
- **payment-executor** reads the mode at commit, books `booked = SettlementRounding.book(precise, scale, mode)`,
  rate-locks `booked`/`mode`/`residual` onto the transaction (transaction-mgmt), and emits the residual.
- **revenue-ledger** posts the residual to `REVENUE_ROUNDING` via `postRoundingResidual(ref, residual, ccy)`.
See `docs/MONEY_CONVENTION.md` for the full rule and worked example.

## WBS / backlog impact (tracked)
- WBS work-packages annotated: **3.2, 3.3, 4.8, 5.5, 7.3, 10.3, 15.3**.
- **12 new tickets** added across config-registry (4), transaction-mgmt (1), payment-executor (3),
  revenue-ledger (1), admin-ui (1), qa-platform (2). Backlog total: **3,432 → 3,444**.

## Code status
- ✅ Built + tested: `lib-money` (`SettlementRounding`/`BookedAmount`), `lib-domain` (`Partner.settlementRoundingMode`),
  `revenue-ledger` (`postRoundingResidual`).
- ⬜ Pending (Phase-2 wiring): config-registry persistence/API, payment-executor commit path, transaction-mgmt
  locked fields, admin-ui setting, settlement-reconciliation using booked amount.
