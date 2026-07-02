# Changelog — sim-nepal-qr

All notable changes to the Nepal QR partner simulator.

## [2026-07-02] Fix — accept pay nonce from X-KhaltiNonce header
- `/qrscan-thirdparty/pay/` required `nonce` **inside the signed body** and 400'd
  (`validation_error`) otherwise. The real caller (`scheme-adapter-nepal`) sends the
  nonce as the **`X-KhaltiNonce` header**, not in the body — so every live payment
  declined (the "full payment cycle failed" symptom). Now falls back to the header
  when the body omits it. Existing body-nonce callers unaffected. Regression test T06b.

## [Unreleased]

### Added
- **Operator console web UI** served at the sim root (`resources/static/index.html`
  + `app.js`, mirroring the sim-merchant plain-HTML/CSS/JS style, Nepal-crimson theme).
  So it can be iframed by the admin-ui Simulation Console. Three panels:
  - **QR** — textarea prefilled with the sample Fonepay QR; **Decode** calls
    `POST /qrscan-thirdparty/parse/` and renders network / merchantName / merchantId /
    merchantCity / country / MCC / currency / amount (paisa→NPR).
  - **Pay** — NPR amount (→ paisa), auto-generated unique reference, optional
    mobile/purpose/remarks/outcome; shows idx, status, amount + full JSON response.
  - **Records** — `GET /sim/nepal-qr/records` newest-first, expandable request +
    decoded payload + response; auto-refreshes after a Pay.
- **Same-origin UI convenience endpoint** `POST /sim/nepal-qr/ui/pay`
  (`{qs,amountPaisa,reference,mobile?,purpose?,remarks?,outcome?}`) — runs the same
  scan-and-pay logic as the signed `/qrscan-thirdparty/pay/` (reference dedup, outcome
  approve|pending|reject) and **records** every request/response so UI actions land in
  the inspection store. No signature/nonce required (same-origin, no CORS).
- MockMvc test **T11** covering the UI pay endpoint (txn created + recorded).

### Changed
- `POST /qrscan-thirdparty/parse/` response now also carries `network` and
  `merchantId` (previously only the GUID was surfaced via `merchantInfoExtra`),
  completing the decoded merchant shape.
- `GET /sim/nepal-qr/records` accepts an optional `?endpoint=` filter (in addition
  to `?reference=`) so read-only calls (e.g. parse) can be located in the store.

### Verified / hardened
- Proved `QrParser.parse` decodes the exact real Fonepay wallet QR
  (`...26350011fonepay.com07164089720000001783...5914SudanMerchant6015AathraiTriveni...`)
  field-by-field: network=fonepay, initMethod=static, merchantId=4089720000001783
  (MAI template 26 sub-tag 07), MCC=5412, currency=NPR (tag 53=524), country=NP,
  name=SudanMerchant + city=AathraiTriveni (both declared one char too long — the
  resync heuristic trims the trailing digit instead of eating the next tag),
  amount=null (no tag 54, static). No parser change was needed — added a
  full-field parser test (`decodesExactRealFonepayQr`) plus endpoint tests
  (T05 extended + T05b) asserting the parse and `/api/qr/validate/` responses.

## [0.0.1] - 2026-07-01

### Added
- Initial standalone Nepal QR partner MOCK (Khalti / Fonepay), mirroring the
  `simulators/sim-scheme` structure and Gradle/Spring versions (JDK 21, Spring Boot 3.3.4).
  Own `settings.gradle` + `build.gradle`; **not** in the root `settings.gradle`. Port **9103**.
- **QR Validate API** (`validate.txt`): `POST /api/qr/validate/` + aliases
  `/api/v2/qr/validate/`, `/api/v1/qr/validate/`. `Authorization: Token <t>` (any non-empty
  token). Flat JSON keyed by `network` for khalti / mobank / fonepay / nepalpay / unionpay /
  smartqr. Error envelope `{error:{code,message,detail}}` (invalid_qr, unsupported_qr,
  receiver_not_found, receiver_not_eligible) + 401/403 auth failures. IP-allowlist toggle (off).
- **Issuance Extension / Scan & Pay** (`issuance-extension.txt`), signed API
  (`Authorization: Key <k>` + `X-KhaltiNonce`), body `{data:base64(json),signature}`:
  - `POST /qrscan-thirdparty/parse/` — base64 or raw `{qs}` body; returns merchant fields.
  - `POST /qrscan-thirdparty/pay/` — **creates the txn**; reference dedup; APPROVED default;
    outcome overridable (approve|pending|reject); validation_error / khalti_error cases.
  - `POST /qrscan-thirdparty/status/` — APPROVED / PENDING / REJECTED / REVERSED / Error.
  - Signature accepted as-is (soft-log only, no RSA verify). Nonce-window check toggle (off).
- **Record store** (`NepalQrStore`) — persists every request/response as a `SimRecord`
  incl. base64-decoded payload; transactions keyed by unique reference.
- **Inspection endpoints**: `GET /sim/nepal-qr/records` (newest-first, `?reference=`),
  `GET /sim/nepal-qr/records/{id}`, `GET /sim/nepal-qr/txns/{reference}`.
- Best-effort EMVCo/Fonepay TLV parser (`QrParser`) with a resync heuristic for the doc
  sample's off-by-one text-field lengths; falls back to mock values for unread tags.
- 13 JUnit/MockMvc tests + parser unit tests. README with endpoints, port, sample curls,
  and how GMEPay+ points its Nepal scheme base URL here.
