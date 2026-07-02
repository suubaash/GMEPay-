> 작업: Finance Head audit / 출처: agent

# GMEPay+ — Finance / Treasury / Accounting Gap Audit
**Auditor lens:** Head of Finance / Treasury. Date: 2026-07-01.
**Purpose:** What is still missing for me to close the books, manage liquidity & FX, bill partners, recognize revenue, satisfy auditors & tax, and know which corridors make money.

Scope note: the platform already has strong *transaction-level* accounting — double-entry journals on payment-approved (FX margin + service fee + fee-share split) and on settlement (position + rounding residual), balanced entries, configurable split, an internal chart-of-accounts stub (REVENUE_ROUNDING), settlement rounding policy (Addendum-001), BOK/KoFIU/Hometax reporting, prefunding float with burn-rate forecast + maker-checker. The gaps below are the *finance-department* layer that sits **on top of** those transaction books. None of these are currently built.

---

## TOP GAPS (finance / treasury / accounting)

### A. Books don't leave the platform → corporate accounts
1. **GL export to the corporate ERP.** Journals live only inside revenue-ledger. There is no periodic, agreed-format export (batch file/API) that posts summarized or detailed entries into GME's corporate accounting system with a mapped chart-of-accounts crosswalk. Today I re-key numbers by hand — no audit trail from ERP back to source txn.
2. **Full chart of accounts + account mapping.** Only REVENUE_ROUNDING is registered. There is no complete, versioned CoA (asset/liability/revenue/FX-gain-loss/suspense per currency) that the journals post to and that maps 1:1 to the corporate ledger. Without it the export in (1) has nowhere to land.
3. **Trial balance / period-close & lock.** No month-end close: no trial balance per entity/currency, no "close the period, lock journals, no back-dating" control, no reopen/adjustment-entry workflow. I cannot certify a closed month.

### B. Cash is not reconciled to the bank
4. **Bank / nostro reconciliation.** We match scheme files (ZP0062/64) but never match **actual bank & SWIFT receipts** — e.g. the partner→GME inbound SWIFT credit, or our outbound payout debit — against the *expected* settlement amount. Cash actually received vs booked is unverified; this is the single biggest audit exposure.
5. **Suspense / unidentified-funds account.** No holding account + aging workflow for money that arrives without a matching txn (partial credit, wrong reference, FX fee shortfall on the SWIFT). Today an unmatched bank credit has nowhere to sit.
6. **Value-dating & settlement finality.** Journals book on approval/file-send, but there is no concept of *value date* (when cash is actually good) or a finality flag. Interest, float cost, and "is this receivable actually settled" all depend on value date, which we don't track.

### C. Treasury / liquidity blind spots
7. **Consolidated multi-currency cash position.** Prefunding tracks per-partner/per-scheme float, but there is no single treasury view of total KRW / USD / NPR cash & near-cash across all accounts and floats at a point in time. I can't answer "how much USD do we hold right now?"
8. **FX open-position / exposure monitoring.** We state "GME bears FX risk in-window," but nothing measures the *live net open USD (and NPR) position*, VaR/limit, or intraday exposure. FX risk is assumed but unmeasured and unlimited.
9. **Funding forecast across currencies + hedging hooks.** Burn-rate forecast is per-float only. No forward cash-flow forecast (payouts due NPR, top-ups due USD, receipts due KRW) to tell treasury when/how much to buy or hedge, and no place to record hedge deals against exposure.
10. **Interest / cost of float.** No accrual of interest earned on balances or funding cost on prefunding/credit — a real P&L line and a corridor-profitability input that is currently invisible.

### D. Billing partners & recognizing revenue
11. **Partner billing / statement of account & invoicing.** We only produce the Hometax *gross-merchant* tax invoice. There is no periodic **statement of account per partner** (service fees owed, FX margin, MDR share, netting of what GME owes vs what partner owes) and no GME-issued commercial invoice + dispute/credit-note flow. I cannot formally bill a partner or reconcile a disputed fee.
12. **Revenue recognition: earned vs settled + accruals.** Revenue is booked at approval as if realized. There is no split of **earned-but-unsettled (accrued)** vs settled vs **deferred**, and no month-end accrual/true-up (e.g. Phase-2 trading gain, in-window FX not yet settled, fees for txns straddling month-end). Reported revenue is cash-ish, not GAAP-accrual.
13. **Chargeback / refund financial provisioning.** Refunds mirror-reverse at the locked rate, but there is no **provision/reserve** for expected future refunds/chargebacks, nor a liability for in-flight disputes. Month-end has no contra-revenue estimate.

### E. Tax beyond Hometax
14. **VAT/GST on fees + withholding tax.** Only the Korean merchant-fee tax invoice exists. No VAT/GST treatment on GME's *service fees to partners*, no cross-border **withholding tax** handling on fees paid to/from Nepal, and no tax-invoice completeness for non-Korea corridors. Tax filings outside Hometax are unsupported.

### F. Do we make money, and can we prove control?
15. **Profitability / margin analytics per partner × corridor × scheme.** We book revenue but there is no P&L cut that nets revenue **minus** FX cost, float/funding cost, VAN fee, scheme share and refund provision to show contribution margin per corridor. I can't say which corridor is profitable — the core commercial question.
16. **Management financial reporting (P&L, cash-flow, balance sheet extract).** No finance-facing management pack: consolidated P&L, cash-flow statement, and balance-sheet view of floats/receivables/payables. Reporting today is regulatory (BOK/KoFIU/Hometax), not managerial.
17. **Segregation of duties on money movement + regulatory-capital/prefunding adequacy.** Maker-checker exists on top-ups and pricing, but there is no finance-owned SoD matrix over *all* money-movement/journal-adjustment actions, and no monitoring of **prefunding adequacy / regulatory capital** (is float ≥ outstanding obligations + buffer, per licence). This is both an audit control and a solvency guardrail.

---

## Priority for finance sign-off
- **Must-have before go-live / first close:** #4 bank-nostro recon, #1+#2+#3 ERP export + CoA + period close, #11 partner statements/billing, #15 corridor profitability.
- **Must-have for treasury safety:** #7 cash position, #8 FX open-position limit, #17 prefunding adequacy.
- **Needed for a clean audit / GAAP:** #12 accruals, #13 refund provision, #5 suspense, #6 value-dating, #14 VAT/WHT.
