# Changelog — sim-nepal-qr

All notable changes to the Nepal QR partner simulator.

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
