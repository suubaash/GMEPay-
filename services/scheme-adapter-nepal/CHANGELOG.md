# scheme-adapter-nepal — CHANGELOG

All notable changes to the Nepal QR scheme adapter. Newest first.

## 2026-07-01 — Initial adapter (Khalti/Fonepay ACL, real counterpart to sim-nepal-qr)

New microservice: the real Nepal QR scheme adapter that lets payment-executor drive a Nepal
payment against the partner (Khalti/Fonepay) decode → pay → status APIs. Modeled on
`scheme-adapter-zeropay`; targets `sim-nepal-qr` (:9103).

### Added
- **Internal API** `/internal/scheme/nepal` (`NepalSchemeController`):
  - `POST /decode` `{qs}` → `{network, merchantId, merchantName, merchantCity, amountPaisa|null, currency}`.
  - `POST /submit` `{qs, amountPaisa, reference, mobile?, purpose?, remarks?}` →
    `{schemeTxnRef(=idx), status(APPROVED/PENDING/REJECTED), amountPaisa}`. **Authorize+commit combined**
    because the Nepal `pay` call is synchronous single-shot (contrast ZeroPay's two-phase).
  - `GET /status?reference=` → `{state}`.
- **`NepalSchemeApiClient`** — partner REST client binding `/qrscan-thirdparty/parse/` (unsigned decode),
  `/api/qr/validate/` (Token-auth decode), `/qrscan-thirdparty/pay/` and `/qrscan-thirdparty/status/`
  (signed, `Authorization: Key` + `X-KhaltiNonce`). Config-gated base URL / token / key.
- **Signing seam** `NepalRequestSigner` + default **`StubNepalSigner`**: JSON → `nonce`(=epoch secs) →
  base64 `data` + placeholder signature (sim accepts any). Clear TODO for a real RSA-2048/PKCS#1
  signer (NOT implemented — no key material / real endpoint).
- **`NepalSchemeAdapter`** — field-shape mapping (rupee `trxAmount` → paisa; partner `idx` →
  `schemeTxnRef`; `detail` → canonical state) so the controller stays thin.
- **Error mapping** to canonical `com.gme.pay.errors.ErrorCode`: duplicate reference →
  `IDEMPOTENCY_CONFLICT`; nonce/khalti_error/invalid-QR/validation → `VALIDATION_ERROR`;
  401/403/5xx/unreachable → `SCHEME_UNAVAILABLE`; 404 → `MERCHANT_NOT_FOUND`.
  `ApiExceptionHandler` renders the canonical `ApiError` envelope.
- Tests (`MockRestServiceServer`, no running sim): client parse/validate/pay/status happy paths,
  duplicate-reference / nonce-expired / khalti_error / invalid-key error mapping, status states;
  adapter rupee→paisa + state derivation; stub signer envelope round-trip.
- `build.gradle` (web + springdoc + lib-errors; no JPA/Flyway — real-time REST only), `application.yml`,
  `Dockerfile`, `README.md`. Registered in root `settings.gradle`.

### Deferred / externally blocked
- Real **RSA-2048/PKCS#1 signer** with production Khalti key material and live endpoint.
- payment-executor wiring to route Nepal corridors here (smart-router scheme selection).
- Refund/reversal path (partner `REVERSED` state is read by `/status` but no cancel endpoint yet).
