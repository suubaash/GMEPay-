# scheme-adapter-nepal — CHANGELOG

All notable changes to the Nepal QR scheme adapter. Newest first.

## [feat/exec-gap-closure-2026-07-28] - 2026-07-28 (T4-1: real RSA request signer)

### Fixed - the placeholder signature is gone
`StubNepalSigner` was the ONLY `NepalRequestSigner` bean and returned the constant
`c3R1Yi1zaWduYXR1cmU=` for every signed `/pay` and `/status` call. `sim-nepal-qr` soft-logs
signatures, so this passed end-to-end locally and would have failed only against the real scheme.

### Added
- **`RsaNepalSigner`** - the real signer: injects the `nonce`, base64-encodes the JSON into `data`,
  and signs the base64 **text** with `SHA256withRSA` (RSA / PKCS#1 v1.5 / SHA-256, minimum 2048-bit),
  per `API-DOCS/issuance-extension.txt`.
- **Key material from configuration only; no key committed:**
  `gmepay.scheme.nepal.signing.private-key` (PKCS#8 PEM or bare base64 DER, e.g. from
  `GMEPAY_SCHEME_NEPAL_SIGNING_PRIVATE_KEY`), or `...private-key-path` for a mounted secret file.
  Key material is validated at load time (algorithm, PKCS#8 encoding, >=2048 bits) so a bad key fails
  at startup, not at the first payment. Nothing key-related is ever logged.
- **`...signing.mode=EPHEMERAL_DEV`** - explicit opt-in for local/sim runs with no scheme credentials:
  a throwaway RSA-2048 keypair is generated at startup and loudly warned about. The full signing path
  still executes for real; the live scheme holds no matching public key. `docker-compose.yml` sets it
  for the single-host stack (overridable via `.env`); Helm/production leave it unset.

### Changed - fails CLOSED when unconfigured
With no key material and no `EPHEMERAL_DEV`, the bean still starts (so `/decode` and the health probes
work) but every `sign()` throws `503 SCHEME_UNAVAILABLE` with a message naming the missing property. A
signed Nepal request is never emitted with a placeholder signature.

### Removed
- `StubNepalSigner` and `StubNepalSignerTest`.

### Tests
- **`RsaNepalSignerTest`** (7): the signature VERIFIES under the configured public key over
  `base64(data)`; bare-base64 keys accepted; unconfigured -> refusal; the retired constant is never
  emitted and two payloads never share a signature; `EPHEMERAL_DEV` produces a 256-byte RSA-2048
  signature; undersized and garbage keys are rejected at load time.
- `NepalSchemeAdapterTest` / `NepalSchemeApiClientTest` now sign with a real (`EPHEMERAL_DEV`) key -
  there is no constant-signature fallback left to lean on.

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
