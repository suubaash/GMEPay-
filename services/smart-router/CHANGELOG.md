# smart-router CHANGELOG

## [Unreleased] — w3/smart-router — 2026-06-30

### Added (Wave-3 — data-driven scheme resolution over config-registry)
- **`RestPartnerSchemeRegistry`** — the production `PartnerSchemeRegistry`
  adapter backing `LocationSchemeResolver` with LIVE config-registry data
  (V022 `partner_scheme`). Reads the partner directory (`GET /v1/partners`),
  keeps routable partners operating in the requested country, fans out to each
  one's `GET /v1/admin/partners/{partnerCode}/schemes` and maps the returned
  `PartnerSchemeView` rows → the resolver's `PartnerSchemeRecord`. Mirrors
  `RestPartnerSchemeResolver`: Spring 6 RestClient, `gmepay.config-registry.base-url`
  property, two-constructor `@Autowired` trap, and `SCHEME_UNAVAILABLE` (503) on
  any upstream failure (never a silent empty fallback); a 404 on one partner's
  scheme read is treated as a write-race and contributes nothing.
- **Gating**: `@ConditionalOnProperty("gmepay.config-registry.enabled"=true)` so
  the default `InMemoryPartnerSchemeRegistry` fixture stays in place for tests
  and local runs; the in-memory bean remains `@Profile("!config-registry")`.
- **Mapping rules**: drops disabled (`enabled=false`) and non-`ACTIVE` status
  rows; filters by the view's `countryCode` when populated; derives
  CPM/MPM support from `supportsCpm`/`supportsMpm`, falling back to
  `approvalMethodCpm`/`Mpm` presence when the flags are still null; sorts by
  `priority` (null → last). The resolver's `NO_SCHEME_FOR_LOCATION` /
  `DIRECTION_NOT_ENABLED` / `PAYMENT_MODE_NOT_SUPPORTED` branches now run over
  this fetched data unchanged.
- Tests: `RestPartnerSchemeRegistryTest` (MockRestServiceServer, config-registry
  NOT running) — view→record mapping, success resolution, all three data
  branches, and the approval-method fallback. `./gradlew :services:smart-router:test`
  green (37 tests).

## [Unreleased] — p2/smart-router — 2026-06-30

### Changed (Phase 2 — canonical error wiring)
- **Migrated scheme-for-location resolution onto canonical `lib-errors`
  `ErrorCode`.** The new shared contract (lib-errors commit `5dbafd5`) added
  `PAYMENT_MODE_NOT_SUPPORTED` (409) and `DIRECTION_NOT_ENABLED` (409), joining
  the pre-existing `NO_SCHEME_FOR_LOCATION` (404) and `VALIDATION_ERROR` (400).
  `LocationSchemeResolver` now throws `ApiException(ErrorCode…)` directly and the
  router-local `ResolutionError` enum + `SchemeResolutionException` are
  **removed**.
- **Added `RouterApiExceptionHandler`** (`@RestControllerAdvice`) rendering every
  `ApiException` as the canonical API-05 `ApiError` envelope
  (`code`/`message`/`retryable`) with the `ErrorCode`'s HTTP status. This also
  fixes the existing `SchemeRouter` country/partner throws on `GET /v1/route`
  and `GET /v1/route/partners/{code}`, which previously had no advice and fell
  through to Spring's default 500 handler.
- `LocationResolveController` drops its bespoke `ResolveError` record +
  per-controller `@ExceptionHandler`; the bad-mode parse now throws
  `ApiException(VALIDATION_ERROR)`. `GET /v1/route/resolve` error responses now
  use the unified envelope/status (409 for mode/direction).
- Tests updated to assert canonical `ErrorCode`/status; added
  `LocationResolveControllerTest` (MockMvc) asserting the 409/404/400 envelopes
  end-to-end. `./gradlew :services:smart-router:test` green (30 tests).

### Remaining
- The config-registry `partner_scheme` REST adapter is still NOT built (out of
  this pass's scope — config-registry read contract not yet frozen). The
  in-process `InMemoryPartnerSchemeRegistry` fixture remains the resolver's
  backing behind the `PartnerSchemeRegistry` port.

## [Unreleased] — agent/smart-router — 2026-06-30

### Added
- **Data-driven scheme-for-location resolution** (cross-service request from
  qr-service). New `com.gme.pay.router.resolve` package:
  - `PartnerSchemeRegistry` port returning rich `PartnerSchemeRecord` rows
    (schemeId, country, direction, CPM/MPM support, priority) over the
    `partner_scheme` registry (config-registry V022).
  - `InMemoryPartnerSchemeRegistry` in-process fixture (`@Profile("!config-registry")`),
    seeded with the live corridors (KR/ZeroPay, KH/KHQR+BAKONG, VN/NAPAS_247,
    SG/PROMPT_PAY+FAST_SG). The production REST adapter slots behind the port
    once the config-registry read contract lands (INTEGRATION REQUEST #1).
  - `LocationSchemeResolver` — given country/location + payment mode (CPM/MPM)
    + direction, returns the enabled scheme(s) with priority disambiguation
    (`SchemeResolution.ambiguous`), applying four named branches in narrowing
    order: `VALIDATION_ERROR` → `NO_SCHEME_FOR_LOCATION` →
    `DIRECTION_NOT_ENABLED` → `PAYMENT_MODE_NOT_SUPPORTED`.
  - `ResolutionError` router-local enum + `SchemeResolutionException`.
    `PAYMENT_MODE_NOT_SUPPORTED` / `DIRECTION_NOT_ENABLED` are absent from the
    FROZEN `lib-errors` `ErrorCode`; the local enum's `httpStatus` mirrors the
    canonical 400/404 mapping so a later promotion to `ErrorCode` is
    behaviour-preserving (INTEGRATION REQUEST #2).
  - `GET /v1/route/resolve?country=&mode=&direction=` endpoint
    (`LocationResolveController`) mapping each branch to its HTTP status +
    stable wire `code`.
- 12 JUnit tests covering every branch, priority disambiguation, and the
  in-process fixture.

### Notes
- The lenient fake-`UNKNOWN`-merchant bypass (qr-service / merchant-qr-data
  request #2) lives in **payment-executor** `GmeremitPaymentService.pay()`,
  NOT smart-router — smart-router performs no merchant lookup. It is already
  gated by `gmepay.payment.merchant-validation` (default `strict`). Recorded as
  INTEGRATION REQUEST #3 (payment-executor is frozen for this agent).
