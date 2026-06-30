> 작업: smart-router backlog 완성 / 출처: agent

# smart-router build report — 2026-06-30 (branch agent/smart-router)

## Build status
`./gradlew :services:smart-router:test` — **BUILD SUCCESSFUL**. 25 tests pass
(SchemeRouterTest 8, RestPartnerSchemeResolverTest 5, LocationSchemeResolverTest 9,
InMemoryPartnerSchemeRegistryTest 3). Green from a green baseline.

## Code-state assessment
Published backlog for smart-router is empty (0-ticket per the service map). The
real service was already well past the stale %: data-driven country/partner
scheme resolution over `PartnerSchemeResolver` + a REST config-registry adapter
(`RestPartnerSchemeResolver`) with MockRestServiceServer wire tests already
existed and were green. So this run did NOT redo that — it added the richer,
explicitly-requested resolution surface on top.

## Tickets / work done this run
1. **Data-driven scheme-for-location resolution (qr-service request #1) — DONE.**
   New `resolve` package: `PartnerSchemeRegistry` port (rich rows) +
   `InMemoryPartnerSchemeRegistry` fixture (seeded KR/KH/VN/SG corridors,
   `@Profile("!config-registry")`), `LocationSchemeResolver` taking
   country/location + payment mode (CPM/MPM) + direction and returning enabled
   scheme(s) with priority disambiguation, emitting the four named branches
   `VALIDATION_ERROR / NO_SCHEME_FOR_LOCATION / DIRECTION_NOT_ENABLED /
   PAYMENT_MODE_NOT_SUPPORTED` in narrowing order. `GET /v1/route/resolve`
   endpoint maps each branch to status + stable wire code. 12 new tests
   (every branch + disambiguation + fixture).
2. **Strict-mode / fake-UNKNOWN-merchant bypass (request #2) — investigated;
   NOT in smart-router.** The lenient synth lives in payment-executor
   `GmeremitPaymentService.pay()` (frozen). smart-router does no merchant
   lookup and has no synth path. Recorded as INTEGRATION REQUEST #3.
3. CHANGELOG.md created for the service.

## % estimate
Service was already ~90% against its (empty) backlog; with request #1 fully
landed and request #2 confirmed out-of-scope, smart-router is **~95%** of what
it can own locally. Remaining 5% is the two INTEGRATION-gated swaps below.

## Status of the 2 assigned cross-service requests
- **Request #1 (qr-service: data-driven scheme-for-location)** — CLOSED inside
  smart-router as far as possible: resolver + all four branches + disambiguation
  + tests built against the in-process `partner_scheme` fixture. Live wiring to
  config-registry's real V022 read is gated on INTEGRATION REQUEST #1.
- **Request #2 (merchant-qr-data: honor strict-mode / stop synth merchant)** —
  NOT closable in smart-router: the bypass is in payment-executor (frozen). It
  is *already* default-safe (`gmepay.payment.merchant-validation` defaults to
  `strict`; synth only on opt-in `lenient`). Filed as INTEGRATION REQUEST #3.

## INTEGRATION REQUESTS
1. **config-registry → smart-router: V022 partner_scheme read contract for
   location resolution.** Provide a read endpoint returning, per country (or as
   a filterable list), the ENABLED `partner_scheme` rows with: `schemeId`,
   operating `countryCode`, `direction` (INBOUND|OUTBOUND|BOTH), and per-row
   CPM/MPM support (derivable from `approvalMethodCpm`/`approvalMethodMpm`
   non-null), plus a priority/order. smart-router will implement a REST adapter
   for `PartnerSchemeRegistry` against it (profile `config-registry`) replacing
   the in-process fixture. No config-registry code was changed.
2. **lib-errors → add `PAYMENT_MODE_NOT_SUPPORTED` (409) and
   `DIRECTION_NOT_ENABLED` (409) to `ErrorCode`.** These two resolution branches
   have no canonical code today, so smart-router owns a local `ResolutionError`
   enum whose httpStatus mirrors the intended 400/404/409 mapping. Promoting
   them into the frozen `ErrorCode` enum would let the resolver throw the
   canonical `ApiException` and unify error bodies across services. No
   lib-errors code was changed.
3. **payment-executor (+ merchant-qr-data) → harden the lenient fake-merchant
   bypass.** `GmeremitPaymentService.pay()` synthesises
   `MerchantView("UNKNOWN", "Unknown Merchant", ...)` when the merchant-qr-data
   lookup throws, under `gmepay.payment.merchant-validation=lenient`. It is
   already default-`strict` (synth is opt-in), which satisfies the spirit of the
   request, but the recommendation is to (a) keep strict the only non-dev value,
   or (b) remove the synth path entirely so a 404/unreachable merchant always
   hard-fails with `MERCHANT_NOT_FOUND`. smart-router cannot touch
   payment-executor (frozen) and has no equivalent path of its own.

## Remaining (top 3)
1. Implement the production `RestPartnerSchemeRegistry` REST adapter once
   INTEGRATION REQUEST #1 lands (port + fixture already in place for the swap).
2. Migrate `ResolutionError` → canonical `ErrorCode` after INTEGRATION
   REQUEST #2 (behaviour-preserving by design).
3. Wire qr-service to call `GET /v1/route/resolve` in place of its config-driven
   country allow-list (qr-service-side change; out of this worktree's scope).
