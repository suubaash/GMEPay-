> 작업: NEPAL routing wiring / 출처: agent

# NEPAL scheme routing wiring (na/wiring)

Wired the NEPAL scheme end-to-end so a NP QR payment resolves to NEPAL and
dispatches to `scheme-adapter-nepal`. Three services, all additive + gated, each
module's `test` task green.

## Per-service changes

### config-registry — BUILD SUCCESSFUL
- Registered **NEPAL** in `SchemeCatalogService` (`GET /v1/schemes`): country `NP`,
  currency `NPR`, mode `LIVE`, status `ACTIVE` — a second live-adapter scheme beside
  ZEROPAY, slotted right after it. Supported networks
  (khalti/mobank/fonepay/nepalpay/unionpay/smartqr) are carried descriptively in the
  `name` field (the `SchemeCatalogResponse` DTO has no networks field; DTO left frozen).
- Added `NEPAL` to the **V022 `ck_partner_scheme_scheme` CHECK** roster (edited inline —
  `SchemeCatalogServiceTest.catalogRoster_equalsV022DbCheckRoster` parses V022 directly,
  so the value must live there). `PartnerSchemeService.SCHEMES` auto-derives from
  `SchemeCatalogService.schemeIds()`, so catalog / enablement / DB-CHECK stay in sync.
- Tests updated for 8 catalog rows / two ACTIVE schemes.

### smart-router — BUILD SUCCESSFUL
- Added a **NEPAL row for `NP`** to the seeded `InMemoryPartnerSchemeRegistry`
  (`CPM+MPM`, direction `BOTH`, priority 0). Nepal pay is single-phase (submit =
  authorize+commit) and covers both presentment modes, so a NP scan resolves to NEPAL
  in either mode. `LocationSchemeResolver` is data-driven, needed no code change.
- Added resolver + fixture tests (`npResolvesToNepalEitherMode`, `npResolvesToNepal`).

### payment-executor — BUILD SUCCESSFUL
- **Dispatch mechanism:** new `SchemeClientRouter` (now the `@Primary` `SchemeClient`)
  picks a delegate by scheme code — `NEPAL` → new `NepalRestSchemeClient`, everything
  else / unknown / null → the ZeroPay `RestSchemeClient` (default, behaviour + base-url
  UNCHANGED; just lost its `@Primary`). Code read from `request.schemeId()` (submit) or
  the `schemeId` arg (`checkBalance`); `cancelPayment` (no code, ZeroPay two-phase
  concept) routes to default.
- `NepalRestSchemeClient` hits `POST /internal/scheme/nepal/submit` for both MPM and CPM
  (single-phase); maps `{schemeTxnRef,status,amountPaisa}` → the orchestrator's existing
  `Mpm/CpmSubmitResponse` (schemeApprovalCode ← status, schemeTxnRef ← schemeTxnRef,
  approvedAt ← now). `SchemeId` gained `NEPAL=8` (existing 1..7 ids kept stable).
- `MockRestServiceServer` tests prove NEPAL hits the nepal base-url and maps, ZeroPay
  still hits its own endpoint, unknown falls back to default.

## Config keys
- **Nepal adapter base-url:** `gmepay.scheme-adapters.NEPAL.base-url`
  (default `http://localhost:18091`). Pattern: `gmepay.scheme-adapters.<CODE>.base-url`.
- ZeroPay unchanged: `gmepay.scheme-adapter-zeropay.base-url`.

## Remaining (≤3)
1. **Nepal cancel/status not routed.** `NepalRestSchemeClient.cancelPayment` throws
   (adapter has no cancel); the adapter's `/decode` and `/status` endpoints are not yet
   consumed by the executor. Fine for single-phase happy path; wire if a refund/lookup
   path is needed.
2. **`amountPaisa` conversion assumes decimal payout ×100.** Verify the orchestrator's
   payout unit for NP matches the adapter's paisa expectation before go-live.
3. **V022 CHECK edited in place** (not a new migration) to satisfy the roster-parity
   unit test. Safe for H2 test slices; on a persistent PG a Flyway repair/validate may
   be needed since the V022 checksum changes.
