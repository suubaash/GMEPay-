# smart-router CHANGELOG

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
