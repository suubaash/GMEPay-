# GMEPay+ Money-Path Services — Feature & Choreography Digest

**Date:** 2026-07-01 · **Scope:** the microservices that move money on a QR payment.
**Sources:** `docs/INTER_SERVICE_CONTRACTS.md`, `Documentation/SETTLEMENT_FLOW_SPEC.md`,
`docs/adr/ADR-016`, service backlogs, and the live code under `services/<svc>/src/main/java`.
Where code and older backlog docs disagree, the code + SETTLEMENT_FLOW_SPEC are authoritative
(the two-phase authorize→confirm model has replaced the older single-shot deduct-before-submit design).

---

## 1. What each service does (plain language) + who it talks to

**payment-executor** — the orchestrator; the only service that knows the *whole* payment story and the
order money must move in. It owns two entry surfaces: the partner/API path (`PaymentController` →
`PaymentOrchestrator`, two-phase MPM + single-shot CPM) and the wallet-scan path (`WalletPayController`
`POST /v1/pay` → `FailoverPaymentRouter` / `GmeremitPaymentService` / `SendmnPaymentService` /
`NepalPaymentService`). It calls **rate-fx** (load the locked quote), **qr-service / merchant-qr-data**
(resolve the merchant), **prefunding** (reserve/capture/release the partner float), **smart-router**
(resolve candidate partners for a scanned QR), the **scheme adapters** (balance-check, submit, cancel),
**transaction-mgmt** (create PENDING, commit APPROVED/FAILED/UNCERTAIN), and **revenue-ledger** (post
margins, service fee, commission split, rounding residual). It enforces the non-negotiable: the
irreversible scheme submit happens *last*, only after the partner has charged the customer.

**transaction-mgmt** — the transaction system of record. Owns the txn table, the append-only 8-step
event trail, the state machine (QUOTED→…→APPROVED/FAILED/UNCERTAIN/REVERSED/REFUNDED), idempotency on
`txn_ref`, and the transactional **outbox**. It exposes internal create/commit/status/trail endpoints
and, from the outbox, emits `transaction.*` / `payment.approved` events. It consumes nothing — other
services call it. Downstream (revenue-ledger, notification-webhook) react to its events.

**scheme-adapter-zeropay** — the anti-corruption layer for ZeroPay (the Korean QR scheme). Exposes
internal `/internal/scheme/zeropay/{balance-check, submit, cpm, cancel, health}`. It runs the
**two-phase** scheme interaction: `balance-check` (does GME's prepaid scheme float cover the payout?),
`submit`/`commitPayment` (pay the merchant against that float), `prepareCPM`, and `cancelPayment`. It
also runs the SFTP batch machinery (ZP0011/0021/0061/0065… files) for merchant sync and settlement
tie-out, and writes committed-txn enrichment back via a port to transaction-mgmt.

**scheme-adapter-nepal** — the anti-corruption layer for Nepal networks (Fonepay / NepalPay / Khalti).
Exposes `/internal/scheme/nepal/{parse, submit, status}`. Unlike ZeroPay it is **single-phase**: Nepal
`pay` is synchronous single-shot (authorize+commit combined). It decodes the QR itself (the merchant is
*not* in the Korean merchant store), converts rupees→paisa (×100), submits, and maps the partner
response to a canonical `{schemeTxnRef, state, amount}`. `status(reference)` is the idempotent lookup
used by the failover guard.

**qr-service** — QR generate/parse. Parses EMVCo TLV ZeroPay merchant QRs (`POST /v1/qr/parse`,
`ZeroPayQRParser` + CRC verify) and generates/mints CPM tokens (`POST /v1/qr/cpm/generate`,
`CpmGenerateService`, session store + expiry). On the CPM prepare path it resolves the merchant
(merchant-qr-data port) and reserves the partner float (prefunding port). It is the QR codec; it does
not move money itself.

**smart-router** — scheme/partner resolution. `GET /v1/route?country=` returns the ordered scheme list
for a merchant country; `GET /v1/route/partners/{code}` the per-partner override. Data-driven from
config-registry's `partner_scheme` table (no code change to add a partner). Per ADR-016 it resolves a
QR's **network identifier** → an **ordered candidate list** of partners `(network, country, mode,
direction)` by priority; payment-executor's `FailoverPaymentRouter` walks that list with failover.

**prefunding** — the partner-float ledger. Owns each partner's prepaid balance + credit limit and the
ledger entries. Exposes the money-safety primitives the orchestrator needs: **reserve** (hold, not
debit), **capture** (turn a hold into a debit), **release** (drop a hold), **deduct** (legacy direct
debit), **reverse** (credit back on cancel/refund), plus **chargeCumulative/reverseCumulative** for AML
daily/monthly/annual + velocity caps, and `setCreditLimit`. `available = balance + (allow_credit ?
credit_limit : 0)`; all deductions are atomic per-partner (SELECT … FOR UPDATE). Emits `prefunding.low`.

**rate-fx** — the FX quote engine. `POST /v1/rates` prices a quote (cost rate ± buffer + GME margin +
service fee, USD-intermediary two-leg), `POST /v1/quotes` stores it with a TTL (default 900s), and
`GET /v1/quotes/{id}` replays the locked quote. Pulls cost/treasury rates from config-registry and an
external source (XE) on a schedule. The orchestrator loads (never recomputes) the locked quote at
authorize; GME honours the locked rate through settlement (GME bears FX risk, bounded by TTL).

**merchant-qr-data** — the Korean merchant + QR mirror (MongoDB). `GET /v1/merchants/{qr}` resolves a
scanned ZeroPay QR to a validated merchant (id, name, type, active flag). Kept fresh by SFTP files
synced from ZeroPay (`MerchantSyncScheduler`). Used only for Korean/ZeroPay merchants — Nepal QRs are
resolved by the Nepal adapter, not here.

---

## 2a. Inbound ZeroPay choreography (overseas partner's customer pays a Korean merchant)

Two-phase authorize→confirm (`PaymentOrchestrator.authorizeMpm` / `confirmMpm`); OVERSEAS partner (float
in USD), payout in KRW.

1. **Quote (pre-step).** Partner app → **rate-fx** `POST /v1/rates` + store: rate-fx prices KRW payout →
   +GME margin +service fee → a locked USD/collection-ccy quote with a TTL. Quote id returned to partner.
2. **Authorize — load & agree.** Partner → **payment-executor** `authorize {quoteId, collectionAmount,
   partnerTxnRef,…}`. Executor → **rate-fx** `GET /v1/quotes/{id}` to load the locked quote, then
   asserts the partner-echoed `collectionAmount/currency` matches it exactly (`QuoteAmountMismatch` if
   not).
3. **AML per-txn gate.** Executor computes `holdUsd = collectionUsd + serviceFeeUsd`, resolves the
   partner's limits (config-registry) and enforces the per-transaction USD cap *before any side effect*.
4. **Resolve merchant.** Executor → **qr-service/merchant-qr-data** `resolve(merchantQr)` → validated
   merchant (id, type).
5. **Create PENDING txn.** Executor → **transaction-mgmt** `createPending(...)` (snapshots merchant-fee
   rate + the locked-quote margin pool). Txn is now INITIATED/PENDING.
6. **Reserve the float (hold, not debit).** OVERSEAS only: Executor → **prefunding** `reserve(partnerId,
   txnRef, holdUsd)`. The hold = payout-cost + FX-margin + **service-fee** (whole agreed amount). On
   insufficient float it declines here and fails the orphan txn — nothing owed. Then `chargeCumulative`
   applies the AML daily/monthly/annual + velocity caps under prefunding's per-partner lock; a breach
   voids the authorization (release hold + fail txn).
7. **Scheme balance-check.** Executor → **scheme-adapter-zeropay** `POST /balance-check(schemeId,
   payoutKrw, KRW)`: does GME hold enough prepaid float *with ZeroPay*? A short scheme float declines
   **now**, before the customer is charged (voids the authorization). Success returns an auth context,
   persisted and replayed into confirm. **No scheme submit yet.**
8. **Partner charges the customer.** Out of band: the partner debits the customer's wallet in the
   customer's currency (only the partner can charge its own customer).
9. **Confirm — the irreversible submit.** Partner → **payment-executor** `confirm {authId, wallet-charge
   ref}`. Executor → **scheme-adapter-zeropay** `POST /submit` → ZeroPay pays the Korean merchant against
   GME's scheme float. This is the only irreversible step and it is last.
10. **Capture + commit.** On scheme approval: Executor → **prefunding** `capture(partnerId, txnRef)`
    (hold → real debit); books settlement (per-partner rounding); → **transaction-mgmt** `commitStatus
    APPROVED` with scheme txn ref/approval code + locked margins (transaction-mgmt writes step-6
    committed + emits **payment.approved** via outbox).
11. **Post revenue.** Executor → **revenue-ledger** `postRevenueCapture` (FX margin + service fee),
    `postCommissionSplit` (KRW MDR split, if configured), `postRoundingResidual`. Asynchronously
    **notification-webhook** and **revenue-ledger** also react to `payment.approved`.
    - **Failure matrix (confirm):** scheme *decline* → release hold + reverseCumulative + txn FAILED
      (re-throw). Scheme *timeout* → keep the hold, txn **UNCERTAIN** for reconciliation (the one "ugly"
      case: customer charged, scheme unknown → later auto-refund path). Authorization that never
      confirms → `AuthorizationExpirySweeper` releases the hold and fails the orphan txn.
    - **Cancel/refund** (`cancelPayment`/`refundPayment`): scheme cancel + prefunding `reverse` (credit
      float back at the locked rate) + txn REVERSED/REFUNDED + reversal journal to revenue-ledger.

## 2b. Outbound Nepal choreography (GME/partner user pays a Nepali merchant)

Wallet-scan path (`WalletPayController POST /v1/pay` → `FailoverPaymentRouter`); single-phase scheme
pay; ADR-016 QR-classified routing with failover.

1. **Scan & classify.** Wallet → **payment-executor** `POST /v1/pay {qrPayload, amount, partner,
   userRef}`. `QrSchemeClassifier` parses the QR into `(networkIdentifier, country, mode=MPM)` — EMVCo
   sub-tag `00` GUID (e.g. `fonepay.com`, NepalPay GUID) or JSON shape (`khalti`). A ZeroPay network
   (`com.zeropay`) stays on the domestic ZeroPay path; a known **non-ZeroPay** network is routed via the
   failover router (treated as OVERSEAS). Unclassifiable → decline `invalid_qr`.
2. **Resolve candidates.** Executor → **smart-router** `resolve(network, country, MPM, direction)` → an
   ordered candidate list of partners/schemes (by priority, ACTIVE only), from config-registry's
   `partner_scheme`. Empty → decline `unsupported_qr`.
3. **Walk candidates (bounded by max-hops, default 3).** For each candidate the router mints a stable
   `reference` and → the scheme adapter (via `SchemeClientRouter`, schemeId="NEPAL" →
   **scheme-adapter-nepal**) `submit`. Nepal `submit` is **single-phase**: it decodes the QR, resolves
   the merchant itself (no Korean merchant lookup), converts rupees→paisa, and pays synchronously,
   returning `{schemeTxnRef, state, amount}`.
4. **Interpret the outcome per ADR-016:**
   - **APPROVED** → stop, record the transaction and return approved.
   - **Business decline** (`invalid_qr / unsupported_qr / receiver_not_found / receiver_not_eligible /
     insufficient / duplicate_reference / merchant_not_found …`) → **TERMINAL**, no failover (retrying
     another partner cannot help and risks a double-charge).
   - **Technical failure** (timeout / 5xx / connect / SCHEME_UNAVAILABLE) → **anti-double-charge guard**:
     Executor → **scheme-adapter-nepal** `status(reference)`. If APPROVED/PENDING, do *not* fail over —
     surface that outcome. Only NOT_FOUND/REJECTED lets it move to the next candidate.
5. **Exhausted** → decline `SCHEME_UNAVAILABLE`.
6. **Record txn.** On approval Executor → **transaction-mgmt** `createPending` + `commitStatus APPROVED`
   (resilient — a transaction-mgmt outage logs and continues in the sandbox). Every attempt (partner,
   outcome, reason) is written to the execution-attempt trail regardless of outcome.
   - *Note (code TODO):* the sandbox treats the wallet KRW amount as NPR pass-through with **no FX and
     no partner-float reserve/capture** on the Nepal path yet; real KRW→NPR via rate-fx and prefunding
     holds are a documented follow-up. Contrast with the inbound path, which fully reserves/captures.

---

## 3. Where CPM vs MPM and static vs dynamic amount change the steps

- **MPM (merchant-presented).** Customer scans the merchant's QR; the QR + locked quote anchor the
  payment. Inbound ZeroPay uses the full two-phase authorize→confirm (§2a). Outbound Nepal MPM uses the
  scan→classify→failover path (§2b).
- **CPM (consumer-presented).** Merchant terminal scans a **partner-issued token** minted by qr-service
  `POST /v1/qr/cpm/generate` (session store + expiry). In the *code today* CPM runs a **single-shot**
  `PaymentOrchestrator.executeCpm`: create PENDING → **reserve** float (hold) → scheme balance-check →
  `submit` to the scheme (the irreversible charge) → on success **capture** + commit APPROVED. So CPM
  already rides the same reserve/capture money-safety spine, but as one synchronous call rather than a
  separate authorize/confirm split. Per SETTLEMENT_FLOW_SPEC §5, CPM is *not* a different money model —
  the only intended difference is the front door (terminal-scan trigger + partner-scoped token). The
  per-transaction AML cap gate is not yet applied on the CPM path (partnerCode not threaded).
- **Static vs dynamic amount.** Dynamic-amount QRs carry the payable amount in the QR (ZeroPay 전문
  거래구분 420000; Nepal `trxAmount` present → converted to paisa). Static/registered QRs carry no amount
  (ZeroPay 500000; Nepal `trxAmount` null) so the amount comes from the wallet/quote instead. This only
  changes *where the amount is sourced* — the reserve/submit/capture ordering and the two-phase
  non-negotiable are identical either way. ZeroPay CPM uses 거래구분 400000 with the token in field 36.

---

*File: `/d/GMEPay+/code/outputs/agent/features_moneypath_2026-07-01.md`*
