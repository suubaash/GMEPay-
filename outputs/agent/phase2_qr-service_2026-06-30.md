> 작업: Phase2 qr-service wiring / 출처: agent

# Phase 2 — qr-service prefunding reservation wiring (IR-qr-3)

Branch `p2/qr-service`. Edited only `services/qr-service/`; libs + other services untouched.

## Build status
`./gradlew :services:qr-service:test` → **BUILD SUCCESSFUL**, 66 tests, 0 failures.

## Reservation wired
- **At generate (OVERSEAS / outbound):** `CpmGenerateService.createSession` now reserves prefunding
  via `PrefundingReservationPort` after token issuance, idempotencyKey = the CPM token id, txnRef =
  partner_txn_ref. The returned reservationId + reservedUsd + partnerId are persisted on the session
  (`prefund_reserved_usd` reused; `prefund_partner_id` + `prefund_reservation_id` added in Flyway V004).
  Non-OVERSEAS / no-partner = no reserve. 402 overdraw → `QRErrorCode.INSUFFICIENT_PREFUNDING` (→402);
  no session persisted on overdraw.
- **At expiry:** `CpmTokenExpiryScheduler` releases the hold for each expired OVERSEAS session
  (release keyed on cpm_token_id; partnerId+reservationId carried by `ExpiredSession`). Idempotent —
  expired rows aren't re-swept, and release swallows 5xx so the sweep never fails. Decline path reuses
  the same `release(...)` seam.
- **Gating:** `RestPrefundingReservationClient` `@ConditionalOnProperty
  gmepay.prefunding.reserve.enabled=true` (default false); `InMemoryPrefundingReservationFixture`
  `@ConditionalOnMissingBean` covers tests + no-prefunding runs. Tests use MockRestServiceServer.

## Remaining
1. Real `schemePrepareTokenIssuancePort` bean (IR-qr-1) — scheme-adapter/KFTC-cert-gated (external);
   local fallback left in place as instructed. NOT built here.
2. `partnerId` arrives on the generate request (additive field); confirm the upstream caller
   (gateway/BFF partner-scoping) populates it for outbound, else OVERSEAS reserve is skipped.
3. Convert-on-approval (reservation → hard deduct) is owned by the payment/approval path, not qr-service.
