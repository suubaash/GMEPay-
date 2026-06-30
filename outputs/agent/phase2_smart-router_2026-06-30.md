> 작업: Phase2 smart-router wiring / 출처: agent

# Phase 2 — smart-router canonical error wiring

## Build status
`./gradlew :services:smart-router:test` → **BUILD SUCCESSFUL**, 30 tests green.
Edits confined to `services/smart-router/`; libs + other services untouched.

## Codes migrated
Replaced the router-local `ResolutionError` enum + `SchemeResolutionException`
(both **deleted**) with the canonical `lib-errors` `ErrorCode` via
`ApiException`:
- `PAYMENT_MODE_NOT_SUPPORTED` → 409
- `DIRECTION_NOT_ENABLED` → 409
- `NO_SCHEME_FOR_LOCATION` → 404 (pre-existing canonical)
- `VALIDATION_ERROR` → 400 (kept consistent; controller bad-mode parse too)

Added `RouterApiExceptionHandler` (`@RestControllerAdvice`) → unified API-05
`ApiError` envelope (`code`/`message`/`retryable`) with the `ErrorCode` status.
Side benefit: the existing `SchemeRouter` throws on `GET /v1/route` +
`/v1/route/partners/{code}` previously had no advice and fell to 500 — now they
also emit the canonical envelope.

`GET /v1/route/resolve` verified via new MockMvc `LocationResolveControllerTest`:
409 for mode/direction, 404 no-scheme, 400 validation — asserting envelope +
status end-to-end. Resolver unit tests flipped to assert `ErrorCode`.

## Remaining (≤3)
1. config-registry `partner_scheme` REST adapter NOT built (per scope — read
   contract not yet frozen); `InMemoryPartnerSchemeRegistry` fixture still backs
   the `PartnerSchemeRegistry` port.
2. (carryover) honor `gmepay.merchant.strict-mode` / lenient-merchant hardening
   lives in payment-executor, not smart-router.

Committed to `p2/smart-router`. CHANGELOG updated.
