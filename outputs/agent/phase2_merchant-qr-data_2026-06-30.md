> 작업: Phase2 merchant-qr-data wiring / 출처: agent

# Phase 2 — merchant-qr-data strict-mode code wiring

## Build
`./gradlew :services:merchant-qr-data:test --console=plain` → **BUILD SUCCESSFUL** (in-memory, no Docker).

## Codes flipped
Strict-mode `GET /v1/merchants/{qr}` (`MerchantLookupService.getByQrCodeId`), previously all
`MERCHANT_NOT_FOUND`(404) workaround → now precise 422s by status:
- `status == SUSPENDED` → `ErrorCode.MERCHANT_SUSPENDED` (422)
- other non-operational (DEACTIVATED / `active=false`) → `ErrorCode.MERCHANT_DEACTIVATED` (422)
- genuinely-unknown QR → still `MERCHANT_NOT_FOUND` (404) (unchanged)

Lenient default (`strict-mode=false`) behaviour unchanged (still returns 200 for inactive).

## Tests
`MerchantLookupServiceTest`: flipped `strictMode_deactivatedMerchant_isRejected` to assert
`MERCHANT_DEACTIVATED`/422; added `strictMode_suspendedMerchant_rejectedWithSuspendedCode`
(422) + `strictMode_unknownQr_stillThrowsNotFound` (404). Web `MerchantValidationTest`
untouched (exercises lenient default; its 404 assertions are unknown-QR only).

## Scope / remaining
- Edited only `services/merchant-qr-data/`; libs untouched. CHANGELOG updated; committed to `p2/merchant-qr-data`.
- IR-mqd-2 (smart-router honor strict-mode / stop synthesizing UNKNOWN on 404) — separate service, out of scope.
