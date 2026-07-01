> 작업: scheme-adapter-nepal / 출처: agent

# scheme-adapter-nepal — build report

New backend microservice: the real Nepal QR scheme adapter (Khalti/Fonepay), counterpart to
`sim-nepal-qr` (:9103), modeled on `scheme-adapter-zeropay`. Registered in root `settings.gradle`
(`include 'services:scheme-adapter-nepal'`). Base package `com.gme.pay.scheme.nepal`.

## Internal API (payment-executor → adapter), base `/internal/scheme/nepal`
- `POST /decode` `{qs}` → `{network, merchantId, merchantName, merchantCity, amountPaisa|null, currency}`
  (via partner unsigned `/qrscan-thirdparty/parse/`; rupee `trxAmount` → paisa).
- `POST /submit` `{qs, amountPaisa, reference, mobile?, purpose?, remarks?}` →
  `{schemeTxnRef(=idx), status(APPROVED/PENDING/REJECTED), amountPaisa}`. **Authorize+commit combined** —
  Nepal `pay` is synchronous single-shot (documented vs ZeroPay's two-phase; no `/commit`).
- `GET /status?reference=` → `{state}` (via partner `/status/`).

## Partner client + signing seam (`NepalSchemeApiClient`)
Binds `/qrscan-thirdparty/parse/` (unsigned decode), `/api/qr/validate/` (`Authorization: Token`),
`/qrscan-thirdparty/pay/` + `/status/` (`Authorization: Key` + `X-KhaltiNonce`, signed envelope
`{data,signature}`). **`NepalRequestSigner`** seam + default **`StubNepalSigner`** (JSON→nonce=epoch
secs→base64 `data`, placeholder signature; sim accepts any). TODO left for real RSA-2048/PKCS#1
signer (NOT implemented — no key/endpoint).

## Error mapping → `com.gme.pay.errors.ErrorCode` (+ `ApiExceptionHandler` → `ApiError`)
- duplicate reference (400) → `IDEMPOTENCY_CONFLICT`
- nonce expired/mismatch, khalti_error, invalid QR, validation (400/422) → `VALIDATION_ERROR`
- invalid token/key/IP (401/403), 5xx, unreachable → `SCHEME_UNAVAILABLE`
- 404 → `MERCHANT_NOT_FOUND`

## Build / test status
- `./gradlew :services:scheme-adapter-nepal:build --console=plain` → **BUILD SUCCESSFUL**.
- `:compileJava` (whole multi-module project resolves) → OK.
- **14 tests, 0 failures** (`MockRestServiceServer` fakes the sim: parse/validate/pay/status happy
  paths + duplicate-ref/nonce/khalti_error/invalid-key mapping + status states; adapter rupee→paisa
  & state derivation; stub-signer envelope round-trip).

## Config keys
- `gmepay.scheme.nepal.base-url` (default `http://localhost:9103`)
- `gmepay.scheme.nepal.token` (default `sim-token`) — validate Token auth
- `gmepay.scheme.nepal.key` (default `sim-key`) — pay/status Key auth
- `gmepay.scheme.nepal.enabled` (default `true`); `server.port` 8092 / mgmt 8093

## Remaining (≤3)
1. Real RSA-2048/PKCS#1 signer with production Khalti key + live endpoint.
2. payment-executor wiring to route Nepal corridors here (smart-router scheme selection).
3. Refund/reversal path (partner `REVERSED` read by `/status`, but no cancel endpoint yet).
