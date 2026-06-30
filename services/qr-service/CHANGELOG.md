# qr-service — CHANGELOG

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
