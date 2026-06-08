# GMEPay+ — Money & Rounding Convention

## Representation
- Money is **`java.math.BigDecimal` in major currency units** (e.g. `10.20` USD, `50000` KRW) — **never `double`**, never integer minor units.
- DB columns: PostgreSQL **`NUMERIC`** — `NUMERIC(20,8)` for USD-pool/rate intermediates, `NUMERIC(20,<currency-scale>)` for settled amounts.
- API/JSON: money as a **decimal string** (e.g. `"10.20"`) plus an ISO-4217 `currency` field.

## Currency scale (`lib-money/CurrencyScale`)
- `KRW`/`JPY`/`VND` → 0 decimals; default → 2. **Drive from an ISO-4217 config table** (e.g. MNT is ISO-2dp but often used as 0 — a deliberate config decision).

## Precision & rounding in the rate engine
- USD-pool math runs at **full precision** (`MathContext(20)`); rounding is applied **only at output points** (send_amount, collection_amount, payout) so the pool identity holds (tolerance 0.01 USD).
- Engine default rounding mode: **HALF_UP**.

## Per-partner settlement rounding (how partner liabilities are booked)
A partner may book its settlement liability under a **different rounding rule** than GMEPay+'s precise amount (e.g. round-DOWN to 2dp). To keep partner reconciliation exact:

- **Setting:** `Partner.settlementRoundingMode` (`java.math.RoundingMode`, default `HALF_UP`) — configured **per partner** at onboarding, owned by `config-registry`, audit-logged.
- **At transaction creation/commit:** the partner-facing settlement amount is booked using **that partner's** mode via `lib-money/SettlementRounding.book(precise, scale, mode)`, which returns:
  - `booked` — the liability recorded with the partner (under their rule), and
  - `residual = precise - booked`.
- **The booked + chosen mode are rate-locked onto the transaction** (immutable, auditable).
- **Residual → rounding ledger:** `revenue-ledger.LedgerPostingService.postRoundingResidual(ref, residual, ccy)` posts a balanced journal to account **`REVENUE_ROUNDING`**:
  - residual > 0 (booked less than precise) → rounding **GAIN** (REVENUE_ROUNDING credited),
  - residual < 0 (booked more than precise) → rounding **LOSS** (REVENUE_ROUNDING debited),
  - residual == 0 → nothing posted.

This gives **exact partner reconciliation** (we book under their rule) **and a balanced internal ledger** (the difference is captured as visible rounding gain/loss, never silently absorbed).

### Worked example
Precise settlement amount `10500.567`, partner mode `DOWN`, scale 2 →
`booked = 10500.56`, `residual = +0.007` → posted as a rounding gain (REVENUE_ROUNDING credited 0.007).
Under GMEPay+ default `HALF_UP` the same amount books `10500.57`, residual `-0.003` (loss).

### Status
- ✅ `lib-money`: `SettlementRounding.book(...)` + `BookedAmount` + `CurrencyScale.round(value, ccy, mode)` (tested).
- ✅ `lib-domain`: `Partner.settlementRoundingMode` (default HALF_UP, tested).
- ✅ `revenue-ledger`: `postRoundingResidual(...)` balanced gain/loss posting (tested).
- ⬜ Pending wiring: `payment-executor`/`settlement-reconciliation` to resolve the partner's mode from `config-registry` at commit, apply `book(...)`, lock on the transaction, and call `postRoundingResidual(...)`.
