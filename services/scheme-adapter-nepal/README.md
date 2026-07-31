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
- **`RsaNepalSigner`** (the only `@Component`) — injects `nonce = now`, base64-encodes the JSON into
  `data`, and signs the base64 **text** with **RSA / PKCS#1 v1.5 / SHA-256** (`SHA256withRSA`,
  minimum 2048-bit). This replaced `StubNepalSigner`, which returned the constant
  `c3R1Yi1zaWduYXR1cmU=` for every request — accepted by the sim (it soft-logs signatures) and
  rejected by the real scheme, i.e. a defect that only surfaced in production (gap **T4-1**).

**Key material comes from configuration; no key is committed, and the adapter fails CLOSED without
one** (signed `pay`/`status` answer `503 SCHEME_UNAVAILABLE`; `/decode` and health are unaffected):

| property | meaning |
|---|---|
| `gmepay.scheme.nepal.signing.private-key` | PKCS#8 key, PEM or bare base64 DER (env `GMEPAY_SCHEME_NEPAL_SIGNING_PRIVATE_KEY`) |
| `gmepay.scheme.nepal.signing.private-key-path` | path to a mounted secret file with the same |
| `gmepay.scheme.nepal.signing.mode=EPHEMERAL_DEV` | **local/sim only:** mint a throwaway RSA-2048 keypair at startup (loudly logged). The real signing path still runs; the live scheme holds no matching public key. |

A PKCS#1 key (`BEGIN RSA PRIVATE KEY`) must be converted first:
`openssl pkcs8 -topk8 -nocrypt -in key.pem -out key.pk8.pem`.
`docker-compose.yml` sets `EPHEMERAL_DEV` for the single-host stack; production must mount the real
Khalti-issued key and leave `mode` unset.

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
