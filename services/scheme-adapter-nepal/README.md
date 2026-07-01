# scheme-adapter-nepal

Anti-Corruption Layer (ACL) for the **Nepal QR payment scheme** (Khalti / Fonepay). It is the
real counterpart to the `sim-nepal-qr` simulator (:9103) and the Nepal sibling of
`scheme-adapter-zeropay`. payment-executor drives a Nepal payment by calling this service's
internal API; the adapter translates those calls into the partner's Khalti Scan&Pay REST API.

## Internal API (consumed by payment-executor)

Base path `/internal/scheme/nepal` (internal-only, not on the public gateway).

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/decode` | `{qs}` → `{network, merchantId, merchantName, merchantCity, amountPaisa\|null, currency}` — resolve a scanned QR. |
| POST | `/submit` | `{qs, amountPaisa, reference, mobile?, purpose?, remarks?}` → `{schemeTxnRef, status, amountPaisa}` — pay. |
| GET  | `/status?reference=` | → `{state}` — look up a submitted payment. |

### One-shot vs two-phase

ZeroPay exposes **authorize** then **commit**. Nepal's partner `pay` is **synchronous and
single-shot** — there is no separate commit. So `/submit` here is **authorize+commit combined**;
a `200` from the partner means the funds movement already happened (state `APPROVED`, or `PENDING`
if the partner defers). There is deliberately no `/commit` endpoint.

## Partner REST client → sim-nepal-qr (:9103)

`NepalSchemeApiClient` binds the four partner surfaces (see `simulators/sim-nepal-qr/API-DOCS/`):

| Partner call | Auth | Body |
|--------------|------|------|
| `POST /qrscan-thirdparty/parse/` (decode, unsigned) | none | `{qs}` |
| `POST /api/qr/validate/` (alt decode) | `Authorization: Token <token>` | `{qr}` |
| `POST /qrscan-thirdparty/pay/` | `Authorization: Key <key>` + `X-KhaltiNonce:<nonce>` | signed envelope |
| `POST /qrscan-thirdparty/status/` | `Authorization: Key <key>` + `X-KhaltiNonce:<nonce>` | signed envelope |

`/decode` uses `/parse/` (always available, no token) and converts the partner's rupee `trxAmount`
to paisa. `validate()` is provided for the Token-auth decode surface.

### Signing seam

Signed calls (`pay`, `status`) send `{"data": base64(json), "signature": <sig>}` where the inner
JSON carries a `nonce` (Nepal-time UNIX seconds) equal to the `X-KhaltiNonce` header.

- **`NepalRequestSigner`** — the seam.
- **`StubNepalSigner`** (default `@Component`) — injects `nonce = now`, base64-encodes the JSON into
  `data`, returns a **placeholder signature** (the sim accepts any). Sufficient end-to-end vs the sim.
- **TODO — real signer:** a production `RsaNepalSigner` must sign `SHA-256(base64(json))` with
  **RSA-2048 / PKCS#1 v1.5** using the Khalti-provided private key. Not implemented here (no key
  material / no real endpoint). See the Javadoc on `StubNepalSigner`.

## Error mapping (partner → canonical `ErrorCode`)

| Partner response | `ErrorCode` |
|------------------|-------------|
| 400 `reference: "Duplicate reference..."` | `IDEMPOTENCY_CONFLICT` |
| 400 `detail` contains "Nonce" (expired / mismatch) | `VALIDATION_ERROR` |
| 400 `error_key=khalti_error` (Invalid QR / Payment failed) / other 400 / 422 | `VALIDATION_ERROR` |
| 401 / 403 (bad token/key/IP) | `SCHEME_UNAVAILABLE` (config problem) |
| 404 | `MERCHANT_NOT_FOUND` |
| 5xx / network unreachable | `SCHEME_UNAVAILABLE` |

`ApiExceptionHandler` renders `ApiException` as the canonical `ApiError` envelope with the code's
HTTP status.

## Config keys

| Key | Default | Meaning |
|-----|---------|---------|
| `gmepay.scheme.nepal.base-url` | `http://localhost:9103` | sim-nepal-qr base URL |
| `gmepay.scheme.nepal.token` | `sim-token` | `Authorization: Token` for `/api/qr/validate/` |
| `gmepay.scheme.nepal.key` | `sim-key` | `Authorization: Key` for `/pay/` + `/status/` |
| `gmepay.scheme.nepal.enabled` | `true` | service enable flag |
| `server.port` | `8092` | app port (mgmt `8093`) |

## Build / test

```
./gradlew :services:scheme-adapter-nepal:build --console=plain
```

Tests use `MockRestServiceServer` to fake sim-nepal-qr (no running sim needed). No batch/JPA/Flyway
layer — this adapter is real-time REST only.
