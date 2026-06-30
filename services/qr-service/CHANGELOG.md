# qr-service — CHANGELOG

## 2026-06-30 — Phase 2: prefunding reservation wiring (OVERSEAS CPM)

### Added
- **Prefunding reservation port** (`PrefundingReservationPort`, `reserve`/`release` + `Reservation`
  record) — the seam for the OVERSEAS soft-hold (IR-qr-3).
- **Gated REST client** (`RestPrefundingReservationClient`, `@ConditionalOnProperty
  gmepay.prefunding.reserve.enabled=true`): calls prefunding
  `POST /internal/v1/prefunding/{partnerId}/reserve|release` via `RestClient`
  (`PrefundingReserveRequest`→`PrefundingReserveResponse`, `PrefundingReleaseRequest`). 402 overdraw →
  `QRErrorCode.INSUFFICIENT_PREFUNDING` (→402); release is best-effort + idempotent (never fails the sweep).
- **In-memory fallback** (`InMemoryPrefundingReservationFixture`, `@ConditionalOnMissingBean`) so
  tests + no-prefunding runs work unchanged.
- Flyway `V004`: `prefund_partner_id` + `prefund_reservation_id` columns on `cpm_prepare_session`
  (both nullable) so the expiry sweep can release the hold.
- Tests: `RestPrefundingReservationClientTest` (MockRestServiceServer: reserve, 402→INSUFFICIENT_PREFUNDING,
  release, release swallows 5xx), `CpmTokenExpirySchedulerTest` (release keyed on cpm_token_id, idempotent,
  empty sweep no-op), plus reserve-at-generate + overdraw cases in `CpmGenerateServiceTest` and an OVERSEAS
  persistence case in `CpmSessionPersistenceH2SliceTest` (66 tests total, green on H2).

### Changed
- `CpmGenerateService.createSession` now takes `prefundReserveUsd` + `partnerId`; for `outbound`
  (OVERSEAS) it reserves prefunding (idempotencyKey = the CPM token id) before persisting and stores the
  reservation handle on the session. LOCAL/inbound unchanged (no reserve).
- `CpmTokenExpiryScheduler` releases the prefunding hold for expired OVERSEAS sessions.
- `CpmSessionStorePort.save` carries a `PrefundReservation`; `expireOverdue` returns `ExpiredSession`
  records (cpmTokenId + partnerId + reservationId).
- `CpmGenerateRequest` gains optional `partnerId`.
- The scheme-issued `PrepareTokenIssuancePort` (IR-qr-1) stays the existing local fallback — the real
  `schemePrepareTokenIssuancePort` bean remains scheme-adapter/KFTC-cert-gated (external).

## 2026-06-30 — CPM prepare-token issuance, persistence, lifecycle & CPM parse

### Added
- **Prepare-token issuance port** (`PrepareTokenIssuancePort`) with `CpmPrepareContext` /
  `PrepareTokenResult` records — the clean seam for the scheme-adapter-issued `prepare_token`
  + prefunding reservation (INTEGRATION REQUEST #1). `LocalPrepareTokenIssuer` is the
  self-contained fallback (`@ConditionalOnMissingBean`), marking tokens `schemeIssued=false`.
- **Genuine EMVCo CPM payload encode/parse** (`CpmPayloadEncoder` + `CpmPayloadParser`):
  CRC-protected TLV envelope using a tag-85 CPM template; `parseCpmToken` round-trips token +
  scheme and reuses the shared `EMVCoTlvParser` / `EMVCoCrcVerifier`. `CpmTokenPayload` record
  with `isExpired()` (WBS 5.4-T11).
- **CPM session persistence** (`cpm_prepare_session`, Flyway `V003`): entity, repository, port
  (`CpmSessionStorePort`) + JPA adapter. Generated tokens are now persisted (status `ISSUED`),
  looked up by `payment_id`, and unique on `partner_txn_ref` (WBS 5.3-T01).
- **Generate orchestration** (`CpmGenerateService`): duplicate-`partner_txn_ref` rejection →
  scheme resolution → token issuance → session persist.
- **Scheme-for-country resolution** (`CpmSchemeResolver`) with genuine `NO_SCHEME_FOR_LOCATION`
  handling, config-driven country allow-list `qr.cpm.zeropay-countries` (default `KR`), and
  scheme-hint validation (WBS 5.3-T04; authoritative smart-router = INTEGRATION REQUEST #2).
- **Expiry sweep** (`CpmTokenExpiryScheduler`, `@Scheduled` every 30s, injectable `Clock`):
  marks overdue `ISSUED` sessions `EXPIRED`, idempotently (WBS 5.3-T10; prefunding-reservation
  release on expiry = INTEGRATION REQUEST #3).
- Exceptions `SchemeUnavailableException`, `DuplicatePartnerTxnRefException`.
- Tests: `CpmPayloadRoundTripTest`, `CpmSchemeResolverTest`, `CpmGenerateServiceTest`,
  `CpmSessionPersistenceH2SliceTest`, `CpmControllerTest` (54 tests total, green on H2).

### Changed
- `CpmTokenGenerator` now delegates opaque-token production to `PrepareTokenIssuancePort`
  instead of fabricating the token inline; returns provenance (`schemeIssued`).
- `CpmController` now drives `CpmGenerateService` and maps error codes to correct HTTP statuses
  (409 duplicate, 402 insufficient-prefunding, 401 invalid-signature, 422 otherwise).
- `QrServiceApplication` annotated `@EnableScheduling`.
