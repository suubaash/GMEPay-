# scheme-adapter-ninepay — CHANGELOG

All notable changes to the 9Pay (Vietnam) payout scheme adapter. Newest first.

## 2026-07-27 — Initial adapter (9Pay disbursement ACL, plan Phase 3)

New microservice: the 9Pay payout edge per `Documentation/schemes/QR_SCHEME_ACCOMMODATION_PLAN.md`
Phase 3 (adapter service only — hub payout orchestration deferred, decision D4). Modeled on
`scheme-adapter-nepal` (service shape) + `scheme-adapter-zeropay` (persistence/Flyway). Port 8096.

### Added
- **Hub-facing API** (`NinepaySchemeController`): `POST /scheme/payout`,
  `GET /scheme/payout/{requestId}`, `GET /scheme/balance`, `POST /scheme/decode-qr`, and the
  **inbound IPN edge** `POST /scheme/ipn` (9Pay pushes; raw body audited verbatim).
- **`NinepaySigner`** — RSA-2048 signatures over pipe-delimited canonical field strings,
  base64; digest algorithm configurable (SHA1–SHA512, default SHA256); verifies 9Pay
  response/IPN signatures with 9Pay's public key; PEM key material from config (lazy-parsed
  placeholders).
- **`NinepayApiClient`** — verify / transfer / transfer-info / balance / bank-list /
  exchange-rate-v2 / decode-qr per the integration digest; `hl=en` header; failure taxonomy
  split into definitive (`NinepayErrorException`, carries `error.code`) vs ambiguous
  (`NinepayTransportException` — must poll before retry).
- **`NinepaySchemeAdapter`** — idempotency spine: `np_payouts` row persisted before the wire
  call (UNIQUE `request_id`); 1062 duplicate → adopt via `transfer/info`; ambiguous
  timeout → always poll, never resubmit, surface `UNKNOWN`; VND validation (integer, ≥2,000;
  content charset). IPN handling: signature verify → audit event → map codes 000–009;
  **009 = post-SUCCESS bank reversal → payout `REVERSED` + `reversed_at`**; 008 → `HELD`.
- **Status model** (`PayoutStatus`, `NinepayStatusMapper`): SUBMITTED / PENDING / PROCESSING /
  SUCCESS / FAILED / HELD / REVERSED / UNKNOWN; IPN code wins over the status field.
- **Flyway V001**: `np_payouts` + `np_ipn_events` (portable DDL; H2 PG-mode for tests,
  PostgreSQL for production; VND amounts NUMERIC(20,0)).
- **Tests**: signer round-trip/tamper/PEM/algorithm-variants; status+IPN code mapping;
  adapter idempotency (replay, concurrent dup, 1062, timeout→poll gate, poll-not-found →
  UNKNOWN), VND/content validation, IPN 000/008/009 (incl. reversal after SUCCESS) and
  invalid-signature audit; API client wire tests (MockRestServiceServer); H2 persistence
  slice (unique request_id, CHECK constraints).
