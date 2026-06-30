> 작업: qr-service backlog 완성 / 출처: agent

# qr-service build report — 2026-06-30

## Build status
- `./gradlew :services:qr-service:test` — **GREEN**. 9 test classes, 54 tests, 0 failures, 0 errors.
- H2 (PostgreSQL mode) for unit/slice scope; Testcontainers PostgreSQL IT (`@Tag("docker")`)
  unchanged and CI-only.

## Assessment of actual code state (vs. stale backlog)
The real source is far ahead of the published 47-ticket backlog. Already present & solid before
this run:
- EMVCo MPM parse (`ZeroPayQRParser`), TLV tokenizer (`EMVCoTlvParser`), CRC-16/CCITT verifier
  (`EMVCoCrcVerifier`) with full test vectors — covers WBS 5.4-T04/T05/T06/T07, T13/T14/T15.
- Full exception hierarchy + `QRErrorCode` (5.4-T01/T02).
- `POST /v1/qr/parse` controller with read-through parse cache; `POST /v1/qr/cpm/generate`.
- Merchant-resolution cache + QR-parse cache persistence (Flyway V001/V002), H2 + Postgres ITs
  (17.2-G04).

Genuinely-missing items identified from PRD gap plan (UC-RATE-CPM-PREPARE: CPM token was a locally
fabricated string, never persisted, no scheme seam, no NO_SCHEME handling, no CPM parse).

## Tickets done THIS run (qr-service half; real impls + tests)
1. **CPM prepare-token issuance behind a port** — `PrepareTokenIssuancePort` (+`CpmPrepareContext`/
   `PrepareTokenResult`) and `LocalPrepareTokenIssuer` fallback (`schemeIssued=false`). Replaces
   the inline fabrication in `CpmTokenGenerator`. (WBS 5.3-T06 qr-service half)
2. **Genuine EMVCo CPM encode/parse** — `CpmPayloadEncoder` (tag-85 CRC-protected TLV) +
   `CpmPayloadParser.parseCpmToken` + `CpmTokenPayload`; round-trip + CRC/TLV-validation tests.
   (WBS 5.4-T11)
3. **CPM session persistence** — Flyway `V003__create_cpm_prepare_session.sql`,
   `CpmPrepareSessionEntity`/`Repository`, `CpmSessionStorePort` + `JpaCpmSessionStoreAdapter`;
   generated tokens now persisted ISSUED, unique partner_txn_ref, lookup by payment_id.
   (WBS 5.3-T01, qr-service-scoped DB)
4. **Generate orchestration** — `CpmGenerateService` (dup-ref → resolve scheme → issue → persist);
   `CpmController` rewired + HTTP status mapping (409/402/401/422). (WBS 5.3-T07/T08/T09 partial)
5. **NO_SCHEME_FOR_LOCATION handling** — `CpmSchemeResolver`, config-driven country allow-list
   `qr.cpm.zeropay-countries` (default KR), scheme-hint validation. (WBS 5.3-T04)
6. **Expiry sweep** — `CpmTokenExpiryScheduler` (@Scheduled 30s, injectable `Clock`, idempotent);
   `@EnableScheduling` on the app; `ClockConfig`. (WBS 5.3-T10 qr-service half)
7. New exceptions `SchemeUnavailableException`, `DuplicatePartnerTxnRefException`.
8. Tests: `CpmPayloadRoundTripTest` (6), `CpmSchemeResolverTest` (6), `CpmGenerateServiceTest` (3),
   `CpmSessionPersistenceH2SliceTest` (4), `CpmControllerTest` (4) — all green.
9. `CHANGELOG.md` created.

## Completion estimate
qr-service is ~**90%** for its in-service scope (EMVCo parse/CRC, CPM generate contract +
persistence + lifecycle, NO_SCHEME). Remaining ~10% is cross-service (prefunding reservation,
scheme-issued tokens, smart-router) — out of this service's frozen boundary.

## INTEGRATION REQUESTS
1. **scheme-adapter-zeropay — CPM prepare_token issuance.** Provide a real
   `PrepareTokenIssuancePort` bean named `schemePrepareTokenIssuancePort` that calls 한결원's
   real-time CPM prepare API and returns an opaque one-time `prepare_token` + `qrContent` +
   `expiresAt` with `schemeIssued=true`; must throw `SchemeUnavailableException` (→422 retryable)
   on timeout/connection failure. qr-service auto-prefers it over `LocalPrepareTokenIssuer`
   (`@ConditionalOnMissingBean`). Contract: `PrepareTokenIssuancePort.issue(CpmPrepareContext)`.
2. **smart-router / config-registry — authoritative scheme-for-location resolution.** Replace the
   config-driven country allow-list in `CpmSchemeResolver` with a data-driven lookup over
   partner_scheme tables (multi-scheme disambiguation, supported_modes=CPM, direction enablement).
   Needed to fully satisfy 5.3-T04 VALIDATION_ERROR/PAYMENT_MODE_NOT_SUPPORTED/DIRECTION_NOT_ENABLED
   branches.
3. **prefunding-service — reservation at token issuance & release on expiry/decline.** OVERSEAS
   prefunding `reserve()`/`release()` (SELECT FOR UPDATE) at CPM generate and reservation release
   when `CpmTokenExpiryScheduler` expires a session or confirm declines. qr-service persists a
   nullable `prefund_reserved_usd` column ready to carry the reserved amount; INSUFFICIENT_PREFUNDING
   (→402) maps from `QRErrorCode.INSUFFICIENT_PREFUNDING`.

## Remaining (top items, mostly cross-service or deferred)
- CPM confirm flow (5.3-T11/T12) + 8-step event trail (5.3-T24) — require transaction-orchestrator
  + prefunding ledger (frozen).
- HMAC auth + Redis idempotency layer on generate (5.3-T08/T16) — gateway/shared-infra concern.
- OpenAPI documentation of CPM generate + payment.pending_debit webhook (5.3-T22/T23).
