# GMEPay+ Settlement & Transaction-Flow Specification

**Status:** DRAFT for red-line · **Date:** 2026-06-24 · **Source:** product-owner design interview
**Owner:** GME (subash@gmeremit.com) · **Supersedes assumptions in:** the as-built `payment-executor` MPM/CPM paths

> This spec captures the agreed money model for a GMEPay+ transaction end-to-end: who is charged
> what, in which currency, in what order, and how the books square up. It is the single source of
> truth for the settlement build. Where it diverges from current code, the code is wrong, not the spec.

---

## 1. Context & actors

GMEPay+ is a cross-border QR-payment hub. A **foreign customer** (e.g. a Mongolian SendMN wallet
user) pays a **Korean merchant** via a **QR scheme** (ZeroPay first). GMEPay+ integrates with ZeroPay
as a **해외페이 결제사업자** (overseas payment operator) through the KFTC relay centre.

Four parties, **three settlement relationships** plus one billing relationship:

| Party | Relationship to GME | Money direction |
|---|---|---|
| **Customer** | Belongs to the **partner** (not to GME, not to the scheme) | pays the partner |
| **Wallet partner** (SendMN, GME Remit) | GME↔partner: **prepaid float** | partner funds GME |
| **QR scheme** (ZeroPay) | GME↔scheme: **prepaid float** | GME funds the scheme |
| **Merchant** | GME↔merchant: **out-of-band MDR billing** (conditional) | merchant pays GME |

### 1.1 Foundational principle (drives everything)

> **Only the partner can charge the customer.** The customer belongs to the partner. The scheme and
> GME can only **request** the partner to charge. Neither the scheme nor GME ever debits the customer
> directly.

This single rule collapses MPM and CPM into **one money model** and fixes the order of operations
(§4). It also means ZeroPay's "authorize" never debits the customer — it only commits the **merchant
payout against GME's prepaid scheme float**.

### 1.2 The non-negotiable

> **GME must never submit to the scheme (the irreversible "pay the merchant" act) until the
> customer's wallet charge is confirmed by the partner.**

Because only the partner can charge the customer (§1.1), this is always enforced by **GME's ordering**
(§4), never by scheme-side atomicity.

---

## 2. Design decisions (from the interview)

| # | Decision | Detail |
|---|---|---|
| D1 | **Double prefund** | Partner holds a prepaid float **with GME**; GME holds a prepaid float **with the scheme**. Both are debited per txn. |
| D2 | **3-tier rate cascade** | Scheme prices in KRW → **+GME margin** → GME quotes the partner → **+partner margin** → partner quotes the customer. |
| D3 | **GME→partner quote currency is per-partner commercial** | Either **KRW or USD**, per the contract (the `settle_a_ccy` field). Determines where the FX sits (see §3). |
| D4 | **GME bears FX risk** | GME honours the quoted rate; risk is bounded by the quote TTL (**900s** default). Margin must be priced to absorb drift. |
| D5 | **Forecast-driven bulk settlement** | Funding is pushed in bulk on a sales forecast, **not** matched per-txn. Running float balances are the source of truth. |
| D6 | **Two-phase order** | authorize → partner charges customer → confirm → GME submits to scheme. **Scheme submit is last.** (The non-negotiable.) |
| D7 | **Per-partner credit limit** | `available = balance + (allow_credit ? credit_limit : 0)`. `credit_limit = 0` ⇒ hard-decline at zero. |
| D8 | **Per-scheme credit limit** | Whether the scheme extends GME a line **varies per scheme**. The scheme's balance-check API is the runtime authority; GME mirrors the limit for forecasting. |
| D9 | **Three revenue streams** | **FX margin** (always) + **service fee** (always) + **merchant MDR** (conditional, e.g. some ZeroPay cases). |
| D10 | **Service fee is GME→partner** | GME charges the **partner** (`partner_fee_schedule`: fixed + bps); the partner passes it on to the customer. Collected from the partner float. |
| D11 | **MDR billed to merchant, out-of-band** | For ZeroPay the **scheme returns** the merchant fee (전문 field 41) — GME does not compute it. GME bills the merchant directly (periodic), then **shares a cut with ZeroPay** (configurable). Does **not** touch the per-txn flow. |
| D12 | **Refund = full reversal at original rate** | Clean mirror unwind at the locked original rate; no extra FX gain/loss. |
| D13 | **Partner margin disclosure: optional, non-blocking** | GME wants visibility but **never gates** on it; captured per-partner post-contract if the partner agrees; **no cap**. |
| D14 | **CPM = same money model as MPM** | Differs only at the front door (trigger + token), not in the money (because of D1/§1.1). |

---

## 3. The rate cascade & where FX sits

```
Scheme price (KRW, merchant payout)
   └─ + GME margin  ──────────────►  GME's quote TO the partner   (in settle_a_ccy: KRW or USD — D3)
          └─ + partner margin  ────►  partner's quote to the customer (customer wallet ccy)
```

- **GME's engine** produces only **GME→partner** quote (cost + GME margin + service fee). The
  **partner's margin** is added downstream in the partner's app (D13: optionally disclosed, never
  computed by GME).
- **FX location follows D3:**
  - Agreed currency = **KRW** → the partner does the KRW→wallet FX; GME takes margin in KRW; GME bears no FX on the partner leg.
  - Agreed currency = **USD** → GME does the KRW→USD conversion and takes margin in USD; the partner does USD→wallet.
- **Quote lock (D4):** the quote is valid for the TTL (900s). GME honours it through to settlement;
  if the partner doesn't complete in the window, the quote expires (`RATE_QUOTE_EXPIRED`) and must be
  re-quoted.

### 3.1 Worked example (priced by the real RateEngine)

A SendMN customer pays a 50,000 KRW Korean ZeroPay merchant; cost rates 1,350 KRW/USD & 3,450 MNT/USD;
GME margin 1%+1%; service fee 1,000 MNT; agreed partner currency illustrated in MNT terms:

| Figure | Value | Meaning |
|---|---|---|
| Merchant receives | **50,000 KRW** | full payout, no deduction |
| Customer's wallet charged | **131,385.49 MNT** | partner's bill to the customer |
| GME owes the scheme | **50,000 KRW** | merchant payout against GME's scheme float |
| GME collects from the partner | **payout-cost + FX-margin + service-fee** (≈ 37.79 USD pool **+** the 1,000 MNT fee) | debited from the partner float |
| GME gross revenue | **0.756 USD FX margin + 1,000 MNT service fee** | (MDR billed separately where applicable) |

> **Reconciliation fix (D10):** the partner-float debit must equal **payout-cost + FX-margin +
> service-fee**. Today it debits only the pool (excludes the service fee) — a known bug to close.

---

## 4. The unified transaction flow (two-phase)

Same money model for **MPM and CPM**; only the **trigger** differs (§5).

```mermaid
sequenceDiagram
    autonumber
    participant C as Customer
    participant P as Wallet Partner (app)
    participant GME as GMEPay+
    participant PF as Partner float (GME ledger)
    participant SCH as QR Scheme (ZeroPay)

    Note over C,P: trigger (MPM: customer scans merchant QR · CPM: merchant scans partner-issued token)

    rect rgb(235,245,255)
    Note over P,SCH: PHASE 1 — AUTHORIZE (nothing irreversible)
    P->>GME: POST /authorize {quote, amount, partner, customer ref}
    GME->>PF: check available = balance + (allow_credit ? credit_limit : 0); RESERVE
    GME->>SCH: balance-check (GME prepaid + per-scheme credit)
    GME-->>P: AUTHORIZED (auth id)  — or DECLINE (no charge, nothing owed)
    end

    rect rgb(235,255,240)
    Note over C,SCH: PHASE 2 — CONFIRM (customer money is now real)
    P->>C: debit customer wallet
    P->>GME: POST /{authId}/confirm {wallet-charge ref}
    GME->>SCH: submit (0200) — pay merchant against GME scheme float
    SCH-->>GME: approved (0210)
    GME->>PF: CAPTURE the reserved float (debit incl. service fee)
    GME-->>P: SUCCESS (+ signed webhook)
    end
```

### 4.1 Failure / rollback matrix

| Where it breaks | Outcome |
|---|---|
| Partner float short / scheme balance short (Phase 1) | Decline before any charge — clean, nothing owed |
| Customer has no money (Phase 1→2) | Partner never confirms; GME releases the float reservation; scheme untouched |
| Partner authorised but never confirms | Reservation **expires** on a timeout → released automatically |
| **Scheme declines/outage AFTER customer charged** (Phase 2) | The only ugly case: **auto-refund the customer** (partner reverses the wallet debit) + release the reservation. Minimised by doing the scheme balance-check in Phase 1. |

---

## 5. MPM vs CPM (front door only)

| | MPM | CPM |
|---|---|---|
| Trigger | Customer **scans the merchant's QR** in the partner app | Customer **presents a partner-issued token**; the **merchant terminal scans it** |
| Anchor | Merchant QR + locked quote | **Partner-scoped token** (not the `quote_id`, as the stub does today) |
| ZeroPay 전문 | 거래구분코드 **420000** (dynamic) / **500000** (static, registered) | 거래구분코드 **400000** (QR구분 "3"/"B"); token in field 36 |
| Money model | **Identical** — the §4 two-phase | **Identical** — the §4 two-phase |

**Conclusion:** CPM is not a separate money design. It reuses §4 verbatim; the only build work is the
**trigger** (terminal-scan intake) and **minting a partner-scoped token** in `/cpm/generate` (today it
reuses `quote_id` as the token, which is wrong).

---

## 6. Configuration model (per-partner / per-scheme)

### 6.1 Partner setup
- **`settle_a_ccy`** — the GME↔partner settlement/quote currency (KRW or USD). **Must be writable** (today it's stored-but-unwritable — the dangling UI contract).
- **`collection_ccy`** — the currency the partner collects from its customer (informational/cascade).
- **Float currency** — must equal `settle_a_ccy`; **must be configurable** (today hardcoded USD).
- **`credit_limit`** (+ implicit `allow_credit` = limit > 0) — per D7. Needs a write path + wiring into the authorize gate.
- **`partner_fee_schedule`** (fixed + bps) — the service fee (D10); wire into pricing **and** the float debit.
- **Disclosed partner margin** — optional, nullable, non-blocking (D13).

### 6.2 Scheme setup (net-new — today the scheme is a hardcoded `ZEROPAY→KRW` constant)
- **Settlement currency** per scheme (KRW for ZeroPay; KHR/MNT/IDR/THB/RUB for planned schemes).
- **GME→scheme float mirror** + **per-scheme credit limit** (D8).
- **MDR handling flag** — `scheme-returned` (ZeroPay field 41) vs `GME-computed` (`merchant_fee_schedule`).
- **MDR commission split** GME/scheme(/partner) — the configurable engine (already built).

---

## 7. Build deltas (what to implement)

Ordered; the two-phase ledger is the spine — build it once.

1. **Two-phase API + reservation ledger (spine).**
   - `POST /v1/payments/authorize` → check partner float (balance + credit) + scheme balance-check, **reserve** the float, return an auth id.
   - `POST /v1/payments/{authId}/confirm` → accept the partner's wallet-charge ref, run the scheme submit, **capture** the reservation.
   - Prefunding: add **reserve/hold** (distinct from `deduct`); `available = balance + credit_limit`.
   - Authorization **expiry/timeout** (release reservation) + **scheme-outage→auto-refund** path.
2. **GME→scheme float + pre-submit balance-check** — new `schemeClient.checkBalance(...)` against scheme-adapter-zeropay; per-scheme float mirror + credit config.
3. **Settlement-currency config wiring** — make `settle_a_ccy`/`collection_ccy` writable (close the dangling `patchDraftStep6CurrencySplit`); configurable float currency; per-scheme settlement currency; feed both into pricing + booking (today rate-fx is caller-supplied and recon force-books KRW).
4. **Fee correctness** — fold the service fee into the partner-float debit + the GME→partner quote (close the reconciliation gap).
5. **MDR / merchant billing** — consume ZeroPay field 41 (don't GME-compute for ZeroPay); a **merchant-billing/receivables** subsystem (accrue → invoice → collect); apply the GME/ZeroPay commission split. (Separate workstream — does not touch the per-txn ledger.)
6. **Refund = mirror-at-original-rate** — credit the partner float back at the **stored** original amounts, fire the ZeroPay cancel (0400/0410), and request the partner to credit the customer wallet.
7. **CPM trigger** — terminal-scan intake + mint a partner-scoped token; reuse §4.
8. **ZeroPay 전문/TCP codec + socket client** — *go-live blocker, all modes.* The real wire is a 1,000-byte fixed-length 전문 over TCP; today's adapter speaks REST to the simulator.

---

## 8. Open questions (not yet decided — secondary edges)

- **Refund window:** same-day cancel only, or T+n refund? Partial refunds?
- **Velocity / limits:** per-txn / daily / monthly caps enforcement and their currency.
- **Disputes / chargebacks:** flow and fund recovery.
- **Multi-scheme / multi-currency per partner:** can one partner serve several schemes / customer currencies?
- **Idempotency:** keying on the authorize id and on `partner_txn_ref` (today header-only).
- **Scheme reconciliation file:** format/cadence from ZeroPay for balance true-up.

---

## 9. References
- `Documentation/ZeroPay-API-Integration-Parameters.md` — 전문 message catalog (400000/420000/500000), field map, 1,000-byte TCP reality.
- `services/payment-executor/.../domain/PaymentOrchestrator.java` — current `executeMpm` / `executeCpm` (single-shot; to be split per §7.1).
- `services/config-registry/.../db/migration/` — V015 prefunding, V016 currency split, V018–V022 commercial/scheme config.
- `services/rate-fx` + `libs/lib-rate` — the quote engine (RateEngine, StoredQuote).
- `services/settlement-reconciliation` — today per-txn KRW batch matching (to become balance reconciliation per §7.3).
