> 작업: scheme-adapter-sendmn build / 출처: agent

# Build report — services/scheme-adapter-sendmn (Phase 1, 2026-07-27)

New Spring Boot 3.3.4 / Java 21 service implementing the real SendMN (Mongolia, QPay-fronted)
QR scheme edge per `Documentation/schemes/QR_SCHEME_ACCOMMODATION_PLAN.md` Phase 1,
`digest_smn-qr-api_2026-07-27.md`, decisions D2/D3. Pattern copied from scheme-adapter-nepal
(controller contract, two-ctor `@Autowired` convention, error mapping via lib-errors) +
scheme-adapter-zeropay (JPA/Flyway/H2-PostgreSQL-mode test convention).

## Files created (all under `services/scheme-adapter-sendmn/`; settings.gradle was pre-updated; nothing else outside touched except the plan checkboxes)

**Build/config**
- `build.gradle` — web, actuator, data-jpa, springdoc, flyway-core (+flyway-database-postgresql/postgresql/h2 runtime), lib-errors
- `Dockerfile` — mirror of nepal's multi-stage build, EXPOSE 8093
- `src/main/resources/application.yml` — **port 8093** (deviation: plan suggested 8095, but 8095 is ops-partner-bff; 8093 verified free repo-wide), placeholder base-url `http://localhost:9106` (future sim-sendmn), placeholder credentials, `sendmn.envelope.mode: plain`, empty RSA key placeholders
- `src/main/resources/application.properties` — H2 (PostgreSQL mode) datasource + Flyway, zeropay convention
- `src/main/resources/db/migration/V001__create_smn_fx_rates_and_payments.sql` — `smn_fx_rates` (fx_ticker_no UNIQUE) + `smn_payments` (tx_token_no UNIQUE, status CHECK); plain SQL valid on H2+PG

**Main (`com.gme.pay.scheme.sendmn`)**
- `SchemeAdapterSendmnApplication`
- `crypto/` — `SendmnEnvelopeCodec` (seam), `PlainJsonEnvelopeCodec` (default: base64(JSON)), `RsaAesEnvelopeCodec` (best-effort: RSA-4096 OAEP-SHA256-wrapped AES-256-GCM, layout `[2B len][wrapped key][12B IV][ct]` — isolated pending O2), `EnvelopeCodecConfig` (property-switched `plain|rsa`, base64-DER key loading)
- `client/SendmnAuthClient` — header-credential auth, token cache (refresh at 80 min), `invalidate()`
- `client/SendmnSchemeApiClient` — VerifyQr/Confirm/PaymentStatus, envelope wrap/unwrap, S102/S104 → one re-auth+replay; sends BOTH `FX_CUR_CODE` and `FX_CUR_CD` (O3 drift defense) + registered `FX_TICKER_NO`; transport 4xx/5xx → SCHEME_UNAVAILABLE (ambiguous); business RES_CODEs returned as data
- `adapter/SendmnSchemeAdapter` — TX_TOKEN_NO minting (`SMN`+UTC ts+6 alnum) + persistence at verify; Confirm policy: 0→APPROVED, **304→poll**, 991/998/999/timeout/5xx→**poll**, poll-fail→**UNKNOWN (never auto-fail)**, 307→VALIDATION_ERROR loud, other rejects→REJECTED; already-APPROVED replay is a local no-op; APPROVED never downgraded by flaky poll
- `adapter/SendmnStatusMapper` — Decrypted/Processing→PENDING, Approved→APPROVED, else/null→UNKNOWN
- `fx/FxRateService` — registration (idempotent on FX_TICKER_NO), latest-rate lookup (notice_date desc, id tiebreak), `settlementAmount = local/rate` scale-4 HALF_UP + verify (pre-empts 307)
- `api/SendmnSchemeController` — `/internal/scheme/sendmn/{verify-qr, submit-mpm, status/{txTokenNo}}` (D3: nepal-style prefix)
- `api/FxRateController` — **`POST /partner-hosted/fx-rate`** (SendMN→us) + `GET /internal/scheme/sendmn/fx-rate/latest`
- `api/ApiExceptionHandler`, `config/OpenApiConfig`
- `persistence/` — `SmnFxRateEntity/Repository`, `SmnPaymentEntity/Repository` (status enum VERIFIED/PENDING/APPROVED/REJECTED/UNKNOWN)

**Tests (8 classes, 44 tests)**
- `PlainJsonEnvelopeCodecTest` (4), `RsaAesEnvelopeCodecTest` (5: round-trip, fresh-session-key, GCM tamper, missing-key) — codec round-trips
- `SendmnStatusMapperTest` (4) — mapping incl. unknown-never-fails
- `SendmnSchemeApiClientTest` (8, MockRestServiceServer) — envelope+headers, token caching, S104 re-auth replay, 304-as-data, FX_CUR_CODE/CD payload, 5xx/auth-reject mapping
- `SendmnSchemeAdapterTest` (12, Mockito) — 304→poll, timeout→poll→PENDING, poll-fail→UNKNOWN, idempotent replay, no-rate refusal, 307, settlement 10000.00/3373→2.9647
- `FxRateServiceTest` (7) — registration idempotency + settlement-amount verification
- `SmnPersistenceH2SliceTest` (3, @DataJpaTest) — Flyway V001 on H2, unique constraints, latest-rate ordering
- `SchemeAdapterSendmnApplicationTest` (1) — context boot + default plain codec (caught a real two-ctor @Autowired wiring bug during build)

## Test results
- `:services:scheme-adapter-sendmn:test` → **BUILD SUCCESSFUL, 44 tests, 0 failures, 0 errors**
- `:services:scheme-adapter-nepal:test` → **BUILD SUCCESSFUL** (no shared breakage)

## Deviations from the plan
1. **Port 8093, not 8095** — 8095 is ops-partner-bff (docker-compose, TraceNames, admin-ui env). Plan line amended alongside the checkbox tick.
2. Hub endpoints use the nepal-style `/internal/scheme/sendmn/...` prefix (D3 mirror) rather than the bare `/scheme/...` written in the Phase-1 bullet.
3. Crypto class is `SendmnEnvelopeCodec`+impls (per D2 naming), not a `SendmnCryptoService` class.

## Open questions (external, unchanged O1–O4 + new)
- O1 base URLs/credentials/IP whitelist; O2 exact RSA hybrid wire layout (current `RsaAesEnvelopeCodec` layout is OUR guess, pinned by tests); O3 FX_CUR_CODE vs FX_CUR_CD (we send both) + SETTLEMENT_AMOUNT rounding (HALF_UP assumed); O4 no failed/declined terminal status → REJECTED only ever set on definitive synchronous validation rejects.
- NOTICE_DATE stored as raw string (format unconfirmed); latest-rate ordering assumes sortable yyyyMMdd-style values.
- `/partner-hosted/fx-rate` has no auth on it yet (SendMN-side auth expectations unknown) — must be locked down before exposure.
- Not done here (Phase 2+): hub wiring (SchemeClientRouter/SchemeId/SendmnPaymentService switch), classifier, config-registry roster, docker-compose/run-fleet entries, sim-sendmn.
