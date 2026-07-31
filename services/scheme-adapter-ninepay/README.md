# scheme-adapter-ninepay

Anti-Corruption Layer (ACL) for the **9Pay (Vietnam) disbursement/payout scheme** — VND
transfers from a partner-prefunded 9Pay balance to Vietnamese bank accounts, ATM cards and
e-wallets (Momo, ZaloPay, VNPAY, VNPT Money). Unlike the QR-pay adapters (nepal/zeropay
MPM), this is a **payout edge** with an inbound IPN push channel and a persistence layer.

Naming: 9Pay's Java identifier is `ninepay` (packages cannot start with a digit — plan
decision D1); SCHEME_CODE = "NINEPAY", display name "9Pay". Port **8096**.

## REST API

Hub-facing surface (internal-only; hub payout orchestration is a follow-up — plan D4):

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/scheme/payout` | Submit (or idempotently replay) a payout. Integer VND, min 2,000. |
| GET  | `/scheme/payout/{requestId}` | Payout state; non-final rows re-polled at 9Pay (`transfer/info`). |
| GET  | `/scheme/balance` | Prefunded 9Pay balance (`balance_available`). |
| POST | `/scheme/decode-qr` | Decode a VIETQR/VNPAY payload into payout fields. |
| POST | `/scheme/ipn` | **Inbound** — 9Pay pushes status (codes 000–009). Signature-verified, audited verbatim. |

## Idempotency / anti-double-payout (9Pay has NO cancel API)

- `request_id` is globally unique at 9Pay (error **1062** on reuse) and is the UNIQUE key
  of the local `np_payouts` registry, persisted **before** the wire call.
- 1062 on submit → the transfer already exists → resolve via `POST /service/transfer/info`
  (lookup by `TRANSACTION_REQUEST_ID`), never resubmit.
- Ambiguous timeout/5xx → **always poll** `transfer/info` first; if 9Pay confirms
  "not exists" (1005/1021) the row is surfaced as `UNKNOWN` and the retry decision is the
  hub's. This adapter never auto-resubmits and never auto-fails an ambiguous payout.

## Status model

9Pay machine `PENDING → PROCESSING → SUCCESS | FAIL`, plus IPN message codes:
`000` success · `004` not-yet-processed (retryable) · `001/002/003/005/006/007` failed ·
`008` **HELD** by 9Pay pending merchant confirmation (ops path) · `009` **REVERSED** —
bank reversal *after* SUCCESS. Code 009 flips a SUCCESS payout to `REVERSED`, stamps
`reversed_at`, and the persisted `np_ipn_events` row is the reversal record.

## Signing

RSA-2048 over **pipe-delimited canonical field strings** (per-endpoint field order),
base64 into a `signature` field (`NinepaySigner`). Digest algorithm configurable
(`sign-algorithm`: SHA1/SHA224/SHA256/SHA384/SHA512, default SHA256 — 9Pay is told which
one we use). We sign requests with OUR private key; 9Pay responses/IPNs are verified with
9PAY's public key. Keys are config-injected PEMs (PKCS#8 private / X.509 public) —
placeholders in `application.yml` until real key exchange (open issue O8).

## Persistence

Flyway `V001`: `np_payouts` (UNIQUE `request_id`, status lifecycle incl. HELD/REVERSED/
UNKNOWN, VND amounts NUMERIC(20,0), `reversed_at`) + `np_ipn_events` (verbatim raw payload,
code, `signature_valid`). H2 (PostgreSQL mode) for local/tests, PostgreSQL in production —
same convention as scheme-adapter-zeropay.

## Configuration (`gmepay.scheme.ninepay.*`)

`base-url` (default sim-ninepay `http://localhost:9107`; test
`https://stg-api-console.9pay.mobi`, prod `https://api-console.9pay.vn`), `partner-id`,
`sign-algorithm`, `private-key-pem`, `ninepay-public-key-pem`, `verify-responses`
(response-signature verification gate; IPN verification is always attempted). 9Pay also
requires mutual IP whitelisting — enforced at the network layer.

## Open issues (confirm with 9Pay before production)

`transfer_amount` fee-inclusion contradiction (spec 4.2 vs 4.3) · exchange-rate-v2 real
request body · bank-list auth · HTTP status semantics + IPN ACK contract · `ipn_url`
registration mechanism · production keys/credentials.
