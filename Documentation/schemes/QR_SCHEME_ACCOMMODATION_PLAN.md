# QR Scheme Accommodation Plan — SendMN (Mongolia) + 9Pay (Vietnam)

> Source docs: `API Documents/SMN_QRPayment_1.0.2.pdf`, `API Documents/1. (Pay-Out) ... 9Pay.pdf`
> Digests: `Documentation/schemes/digest_smn-qr-api_2026-07-27.md`, `digest_9pay-payout-api_2026-07-27.md`
> Architecture map: `Documentation/schemes/map_scheme-adapter-arch_2026-07-27.md`
> Status legend: [ ] todo · [~] in progress · [x] done

## Current state (2026-07-27)

- **SENDMN corridor exists but is FAKE at the scheme edge**: `payment-executor` `SendmnPaymentService` does the full KRW→MNT FX/prefunding/ledger flow, then submits MPM to **ZeroPay** (`SCHEME_ID="zeropay"`). Real SendMN APIs (Authentication → VerifyQr → Confirm → PaymentStatus) are not called anywhere.
- **9Pay has zero presence** in the codebase.
- Integration seams available: per-scheme adapter service (copy `scheme-adapter-nepal`), `SchemeClient`/`SchemeClientRouter` in payment-executor, `SchemeCatalogService` + `partner_scheme` in config-registry, `SchemeWireCodec` in sim-merchant.
- Known architectural gaps that these two schemes hit: (b) no inbound scheme callback seam (9Pay IPN needs one), (c) settlement not scheme-pluggable, (d) three parallel scheme rosters to keep in sync.

## Phase 1 — scheme-adapter-sendmn (real SendMN scheme edge)

- [x] New service `services/scheme-adapter-sendmn` (pattern: scheme-adapter-nepal), port 8093 (8095 was already taken by ops-partner-bff)
  - [x] `SendmnAuthClient`: POST `/api/Authentication` (Username/AgentCode/AuthKey headers) → token cache (90-min validity, refresh at ~80 min or on 201/auth-expired)
  - [x] `SendmnCryptoService`: RSA-4096 hybrid envelope for `encryptedData` bodies (AES session key + RSA-OAEP wrap — exact envelope pending SendMN clarification; isolate behind interface so only this class changes) — `EnvelopeCodec` seam, `PlainJsonEnvelopeCodec` default + `RsaAesEnvelopeCodec`
  - [x] `SendmnSchemeApiClient`: VerifyQr / Confirm / PaymentStatus (all POST JSON, token + Username/AgentCode headers)
  - [x] Adapter REST API for hub: `POST /scheme/verify-qr`, `POST /scheme/submit-mpm` (maps to Confirm), `GET /scheme/status/{txToken}` — mirror nepal adapter controller contract so `RestSchemeClient` pattern fits
  - [x] Idempotency: partner-generated `TX_TOKEN_NO` per payment; on ambiguous Confirm (timeout/5xx) → PaymentStatus poll before any retry; error 304 = duplicate → treat as already-submitted, poll status
  - [x] Status mapping: Decrypted/Processing → PENDING, Approved → APPROVED; anything else / poll-timeout → UNKNOWN (never auto-fail — ADR-016 anti-double-charge)
  - [x] **FX rate registration hosting**: SendMN calls US → `POST /partner-hosted/fx-rate` endpoint (FX_TICKER_NO/NOTICE_DATE/RATE) persisting registered buy rates; Confirm must send SETTLEMENT_AMOUNT consistent with registered rate (server verifies, error 307)
  - [x] Flyway V001: `smn_fx_rates`, `smn_payments` (tx_token_no unique, payment_no, status, timestamps)
  - [x] application.yml: base-url, credentials placeholders (no real values — sandbox URL not in doc)
- [x] Unit tests: envelope codec, status mapping, idempotent confirm, fx-rate verify

## Phase 2 — hub wiring for SENDMN

- [x] payment-executor: `SendmnRestSchemeClient implements SchemeClient` (SCHEME_CODE="SENDMN"), register in `SchemeClientRouter`
- [x] payment-executor: `SchemeId` — add SENDMN=9
- [x] `SendmnPaymentService`: switch `SCHEME_ID` "zeropay"→"sendmn", route through router (submitMpm carries qrPayload; currency stays KRW→MNT per existing FX flow; wire real MNT amount + lookupStatus)
- [x] `QrSchemeClassifier`: SendMN/QPay QR network identifier (QPay EMVCo AID/GUID `mn.qpay`... — placeholder `qpay`/`sendmn`/tag58=MN until sample QR confirmed) → route SENDMN
- [x] config-registry: `SchemeCatalogService` SENDMN=ACTIVE; migration `V041__partner_scheme_sendmn_ninepay.sql` (V039/V040 were already taken) extends `ck_partner_scheme_scheme` + network_identifier backfill
- [x] payment-executor yml: `gmepay.scheme-adapters.SENDMN.base-url`
- [x] Wiring tests (router dispatch, classifier, catalog) + full payment-executor/config-registry suites stay green
- [x] lookupStatus restart-hardening: `smn_payments.hub_reference` (V002, persisted at verify-qr = before Confirm) + adapter `GET /status/by-reference/{reference}`; hub map miss falls back to it (404→NOT_FOUND, transport→PENDING) — the in-process map is now just a fast path

## Phase 3 — scheme-adapter-ninepay (9Pay payout edge)

- [x] New service `services/scheme-adapter-ninepay` (Java pkg can't start with digit → `ninepay`), port 8096
  - [x] `NinepaySigner`: RSA-2048 SHA-256 (configurable SHA1-512) over pipe-delimited canonical strings, base64; verify 9Pay response/IPN signatures with their pubkey
  - [x] `NinepayApiClient`: verify / transfer / transfer-info / balance / bank-list / exchange-rate-v2 / decode-qr
  - [x] Adapter REST API for hub: `POST /scheme/payout`, `GET /scheme/payout/{requestId}`, `GET /scheme/balance`, `POST /scheme/decode-qr`
  - [x] **IPN inbound endpoint** `POST /scheme/ipn` (9Pay pushes): verify signature, map message codes 000–009; handle **009 = post-SUCCESS bank reversal** (emit reversal event) and 008 = held-pending-confirm
  - [x] Idempotency: unique `request_id`; dup → 1062 = already-submitted; on timeout ALWAYS poll transfer-info before resubmit; no cancel after submit
  - [x] Flyway V001: `np_payouts` (request_id unique, status, ipn history), `np_ipn_events`
  - [x] Unit tests: signer round-trip, IPN verify + 009 reversal, ambiguous-timeout poll gate
- [ ] Hub wiring (payout is a NEW flow shape — disbursement, not MPM pay): minimal Phase-3 scope = adapter service only; hub payout-orchestration = follow-up decision

## Phase 4 — simulators + E2E

- [x] `sim-sendmn`: standalone sim (own settings.gradle, port 9106) implementing Authentication/VerifyQr/Confirm/PaymentStatus + accepts fx-rate registration; plain-JSON envelope mode for local dev
- [x] `sim-ninepay`: sim (port 9107) implementing verify/transfer/transfer-info/balance + fires IPN callbacks (incl. delayed 009 reversal scenario toggle)
- [x] E2E: wallet scans SendMN static QR → verify → confirm → approved, via sim — `e2e-tests` `SendmnAdapterSimE2ETest` (5 simulator scenarios incl. dup-replay / wire-304 / wire-307), green 2026-07-27
- [x] Hub-through E2E: wallet→hub→SENDMN adapter→sim — `e2e-tests` `SendmnHubThroughE2ETest` (classify + KRW→MNT FX pay + settlement@registered-rate + USD prefund-once + insufficient-prefund negative + by-reference restart probe), green 2026-07-27; found+fixed 3 hub wiring bugs (QPay AID `A000000843...` unclassified; partner=SENDMN hijacked by failover router; prefunding deduct wire shape `amountUsd`/`deductedUsd` vs real `amount`/`balance`)
- [x] E2E: 9Pay payout submit → IPN SUCCESS → (toggle) 009 reversal handled — `e2e-tests` `NinepayPayoutE2ETest` (4 simulator scenarios incl. wire-1062 + delayed 009, full mutual RSA + verify-responses=true), green 2026-07-27

## Phase 5 — cross-cutting hardening (from arch gaps)

- [ ] Settlement: SendMN settles USD vs registered rate — needs recon feed (doc silent on file format → OPEN with SendMN); do NOT force into ZP006x builders
- [x] Docker-compose/run-fleet entries for 2 adapters + 2 sims (compose: full profile, hosts 8098/8099 + sims 9106/9107, own postgres-sendmn/-ninepay; fleet: 18096/18097 + sims 9108/9107 — sim-sendmn moved off 9106, taken by sim-nepal-qr; +sim-sendmn Dockerfile)
- [x] Trace-console taps for new edges (hub→SENDMN adapter, adapter→sim ×2, sim-ninepay→adapter IPN) — console-side EDGES 7109-7112 + Txn-Flow bucketing; live fleet visibility comes from the adapters' lib-errors /ingest self-reporter (sims are standalone builds without lib-errors → sim-side traffic appears via the adapters' reports; TraceNames.PORT_NAMES done 2026-07-27 (8093/8096/9106/9107/9108); /__data DevDataController for the new adapters = remaining Java-side follow-up)
- [ ] (Optional, later) extract shared `SchemeAdapter` SPI — 3rd copy of the ACL is the trigger (we now have it; schedule refactor)

## OPEN issues blocking full production readiness (external)

| # | Scheme | Issue |
|---|--------|-------|
| O1 | SendMN | Base URLs (sandbox+prod) not in doc |
| O2 | SendMN | RSA envelope exact spec (C# sample is an image); key-exchange direction contradiction |
| O3 | SendMN | Canonical field name `FX_CUR_CODE` vs `FX_CUR_CD`; SETTLEMENT_AMOUNT rounding rule |
| O4 | SendMN | No documented failed/declined terminal status; recon file format |
| O5 | 9Pay | IPN ACK contract + ipn_url registration method |
| O6 | 9Pay | `transfer_amount` fee-inclusion contradiction (4.2 vs 4.3) |
| O7 | 9Pay | HTTP status semantics; bank-list auth |
| O8 | Both | Production keys/credentials exchange |

## Decisions taken (autonomous, local-dev scope)

- D1: 9Pay Java identifier = `ninepay` (packages can't start with a digit); SCHEME_CODE = "NINEPAY", display "9Pay".
- D2: SendMN crypto isolated behind `SendmnEnvelopeCodec` interface with a plain-JSON dev mode, so O2 doesn't block the rest.
- D3: SendMN adapter mirrors the nepal-adapter REST contract toward the hub (keeps `SchemeClient` seam uniform).
- D4: 9Pay hub-side payout orchestration deferred — adapter-first, since payout flow shape (no MPM/CPM) is a product decision.
