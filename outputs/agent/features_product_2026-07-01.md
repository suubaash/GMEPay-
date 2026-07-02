# GMEPay+ — Product & Feature Digest

_Read-only survey, 2026-07-01. Sources: `Documentation/1. GMEPay+_Business Scenario & UsecaseEN.docx` (10 business scenarios / 30 base UCs), `Documentation/7. PRD Use-Case Audit & Gap Plan.md` (34 UCs w/ verdicts), `code/Documentation/SETTLEMENT_FLOW_SPEC.md` (agreed money model, 2026-06-24), `code/docs/MONEY_CONVENTION.md`, `Documentation/6. Spec Addendum 001` (rounding), `Documentation/5. Service Map.md`. This describes INTENDED product scope, not current build state._

---

## 1. What GMEPay+ is

GMEPay+ is a **global QR-payment hub** operated by GME that lets **wallet/remittance partners** (its "API Clients" — GME Remit in Korea, SendMN in Mongolia, later Ly Hour/TBank etc.) offer their **own customers** the ability to pay **Korean (and later overseas) QR merchants** through local QR **schemes** — ZeroPay first, then Alipay/WeChat/QPay/QRIS/KHQR/Russia FPS. GME sits in the middle as the integration and settlement hub: it parses any QR format (QR Parser), picks the right network (Smart Router), executes the payment (Executor), and reconciles the money in multiple currencies (Settlement). Every **partner × scheme × direction** combination is independently configured for fee, FX margin, and prefunding. The value GME provides: partners get one API to reach many schemes/countries without direct scheme integration; schemes/merchants get incremental cross-border volume; GME earns FX margin + a service fee (+ merchant MDR where applicable) and handles regulatory reporting (BOK / KoFIU / Hometax) on the flow.

## 2. The money model (4-5 lines)

- **Double prefund:** the partner holds a prepaid float **with GME**; GME holds a prepaid float **with the scheme** (ZeroPay). Both are debited per transaction; funding is pushed in bulk on a sales forecast, not per-txn.
- **Only the partner may charge the customer** — the customer belongs to the partner. GME/scheme only *request* the charge. So the order is strictly two-phase: **authorize** (reserve partner float + scheme balance-check, nothing irreversible) → partner debits the customer wallet → **confirm** → only then GME submits to the scheme to pay the merchant, then captures the reserved float. Scheme submit is always last.
- **3-tier rate cascade:** scheme prices the merchant payout in KRW → **+ GME margin** (GME quotes the partner in the contract currency, KRW *or* USD) → **+ partner margin** (partner quotes its customer in wallet currency). GME bears FX risk, bounded by a 900s quote TTL.
- **Domestic (GME Remit, KRW):** no FX; GME just adds a flat **KRW 500** fee (GME-only revenue, not shared) — *net* settlement, GME keeps its share and remits the rest to ZeroPay.
- **Overseas inbound (SendMN):** **2% FX margin** on the xe.com mid-rate + KRW 500 service fee, deducted from USD prefunding in real time — *gross* settlement (GME settles the full amount to ZeroPay, then separately invoices the merchant monthly for MDR and shares **0.21% / a 70:30 cut** with ZeroPay). Merchant always receives full KRW payout, unaware of GME's fee. Revenue = FX margin (always) + service fee (always) + merchant MDR (conditional). Refund = full mirror reversal at the locked original rate.

## 3. Full intended use-case / feature set (34 UCs across 10 business scenarios + cross-cutting)

**Payment (BS-01/02):** UC-01-01 CPM domestic (GME Remit, customer QR scanned by merchant) · UC-01-02 MPM domestic (customer scans merchant static QR, enters amount) · UC-02-01 CPM overseas inbound (SendMN) · UC-02-02 MPM overseas inbound (SendMN).
**Amount calc (BS-03):** UC-03-01 domestic charge/settlement calc (payment + KRW500, no FX) · UC-03-02 overseas charge-settlement calc (USD = (KRW + fee) × (1+margin%)).
**ZeroPay settlement (BS-04):** UC-04-01 payment/refund result registration (ZP0011/0021 → ZeroPay SFTP, ZP0012/0022 back) · UC-04-02 platform settlement reconciliation (ZP0061/0063 requests, ZP0065/0066 detail, ZP0062/0064 results, line-by-line tie-out) · UC-04-03 settlement exception manual processing (ops portal) · UC-04-04 overseas merchant-fee monthly tax invoice (Hometax, VAT, ZeroPay 0.21% share).
**FX (BS-05):** UC-05-01 FX rate fetch & store (xe.com, every 15 min).
**Prefunding (BS-06):** UC-06-01 prefunding balance management (real-time deduct, USD-10k threshold email alert, zero-balance suspend, full history) + UC-PREFUND-TOPUP (top-up/initial deposit/threshold config).
**Merchant/QR data (BS-07):** UC-07-01 merchant data sync (ZeroPay→GME SFTP ZP0041/45/47/51/55) · UC-07-02 QR data sync (ZP0043/0053) · UC-07-03 real-time merchant & QR validation at pay time.
**BOK reporting (BS-08):** UC-08-01 domestic transaction report · UC-08-02 overseas transaction report (FX1014 outbound / FX1015 inbound).
**Scheme/partner expansion (BS-09):** UC-09-01 new QR scheme registration (config-only) · UC-09-02 new API-client/partner onboarding + KYB (8-slice wizard, 4-eyes) · UC-09-03 routing-rule / margin config (per partner × scheme × direction, change history + approval).
**Partner self-service portal (BS-10):** UC-10-01 prefunding balance inquiry · UC-10-02 transaction history inquiry (CSV export, no internal revenue disclosed) · UC-10-03 transaction detail inquiry.
**Cross-cutting UCs (added in the audit):** UC-RATE-QUOTE (rate quote issuance + TTL lock) · UC-RATE-CPM-PREPARE (CPM prepare / location-based scheme selection) · UC-CANCEL-PAYMENT (same-day reversal via partner API) · UC-REFUND-ADMIN (refund via admin portal) · UC-API-AUTH (partner HMAC auth/authorization) · UC-OPS-RBAC (operator RBAC + hash-chained audit) · UC-KOFIU-AML (AML CTR/STR reporting) · UC-WEBHOOK-DELIVERY (signed payment-result webhook to partner) · UC-OPS-DASHBOARD (real-time txn/settlement monitoring) · UC-PARTNERB-MONITOR (Partner-B authoritative quote monitoring).

## 4. Direction / mode taxonomy

- **Trigger mode:** **CPM** (customer presents partner-issued QR/token, merchant terminal scans it) vs **MPM** (customer scans the merchant's QR; static registered QR or dynamic amount-entry). Same money model — differ only at the "front door."
- **Rate-engine direction:** **SEND** vs **RECEIVE** mode over a USD-intermediary pool (see `subash-fx` skill for formulas).
- **Geography:** **domestic** (Korea, GME Remit, no FX) vs **cross-border** (SendMN etc., FX + prefunding).
- **Flow direction:** **inbound** to Korea (foreign customer → Korean merchant via ZeroPay; the Phase-1 focus) vs **outbound** (e.g. Korea → Nepal/other, Phase-2). Settlement style: **net** (domestic) vs **gross** (international, with separate monthly merchant invoicing).
- **Release phases:** Phase 1 (MVP) = GME Remit + SendMN on ZeroPay only (no scheme-selection logic needed); Phase 2 = more clients + schemes (QRIS/KHQR) with Smart Router auto-detect; Phase 2+ = remaining schemes/corridors.

---
_Written to `/d/GMEPay+/code/outputs/agent/features_product_2026-07-01.md`. No code was edited._
