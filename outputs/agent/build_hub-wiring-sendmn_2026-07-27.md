> 작업: SENDMN hub wiring / 출처: agent

## Phase 2 wiring

Phase 2 of `Documentation/schemes/QR_SCHEME_ACCOMMODATION_PLAN.md` — payment-executor + config-registry now route the SENDMN corridor to the real `scheme-adapter-sendmn` (:8093) instead of ZeroPay.

### Files changed

**payment-executor**
- `src/main/java/com/gme/pay/payment/client/rest/SendmnRestSchemeClient.java` — NEW. `SchemeClient` impl, SCHEME_CODE="SENDMN". Folds the adapter's two-step contract (`POST /internal/scheme/sendmn/verify-qr` → mint `txTokenNo`, then `POST /internal/scheme/sendmn/submit-mpm`) into one `submitMpm`; MNT amount as Decimal(18,2); merchantId NOT forwarded (adapter uses the GUID captured at verify-qr). ADR-016 `lookupStatus` via `GET /internal/scheme/sendmn/status/{txTokenNo}` using a bounded in-memory reference→txTokenNo map recorded BEFORE Confirm; known-reference probe failure/UNKNOWN → PENDING (never NOT_FOUND → no double-charge), unknown reference → NOT_FOUND. 400/422→declined, 503/504/transport→timeout. CPM/cancel unsupported (throw).
- `SchemeClientRouter.java` — third delegate registered (`SENDMN` → SendmnRestSchemeClient).
- `domain/SchemeId.java` — SENDMN=9 (verified free; max was NEPAL=8).
- `domain/SendmnPaymentService.java` — SCHEME_ID "zeropay"→"sendmn"; submit now carries the REAL MNT payout (`payAmountMnt`, currency "MNT") + raw `qrPayload`; KRW→MNT FX / ₩500 fee / USD prefunding / ledger flow untouched. NEW step 8b: in-body `UNKNOWN` → `lookupStatus` probe (APPROVED→approved, REJECTED→prefund reversed+declined, else PENDING surfaced, prefund KEPT); in-body `PENDING` → PENDING surfaced, prefund kept (ADR-016 never auto-fail).
- `domain/QrSchemeClassifier.java` — fallback markers `mn.qpay`/`qpay`→"qpay", `sendmn`→"sendmn"; EMVCo tag58=MN with no recognised network → synthetic "sendmn". Placeholders commented (real QPay AID pending sample QR).
- `client/rest/FixtureSmartRouterClient.java` — qpay/sendmn networks → SENDMN candidate (partner 2).
- `domain/FailoverPaymentRouter.java` — `currencyFor("SENDMN")` → MNT.
- `web/WalletPayController.java` — classify fallback: country MN → MNT.
- `src/main/resources/application.properties` — `gmepay.scheme-adapters.SENDMN.base-url=http://localhost:8093`.

**config-registry**
- `scheme/SchemeCatalogService.java` — SENDMN=ACTIVE (MN/MNT), NINEPAY=PLANNED (VN/VND) appended.
- `scheme/PartnerSchemeEntity.java` — `defaultNetworkIdentifierFor("SENDMN")` = "qpay,sendmn" (mirrors backfill).
- `src/main/resources/db/migration/V041__partner_scheme_sendmn_ninepay.sql` — NEW. Drop+re-add `ck_partner_scheme_scheme` with SENDMN+NINEPAY; network_identifier backfill 'qpay,sendmn' for current SENDMN rows. Engine-neutral (no vendor variants needed; V022/V037 precedent).

**Tests** (new/updated)
- NEW `SendmnRestSchemeClientTest` (8), NEW `FixtureSmartRouterClientTest` (3).
- `SchemeClientRouterTest` +2 (SENDMN dispatch, case-insensitive), `SendmnPaymentServiceTest` +5 (sendmn/MNT/qrPayload captor; UNKNOWN→approved/rejected/pending; PENDING keeps prefund), `QrSchemeClassifierTest` +3, `SchemeIdTest` (SENDMN=9).
- `SchemeCatalogServiceTest` — roster now parsed from V041 (the latest CHECK re-declaration); ACTIVE set = ZEROPAY/NEPAL/SENDMN. `SchemeCatalogControllerTest` length 8→10.

### Test results
`gradlew :services:payment-executor:test :services:config-registry:test` → **BUILD SUCCESSFUL** (config-registry 443 tests, 0 failed; payment-executor full suite green incl. all new tests). One iteration: `SchemeCatalogControllerTest` catalog-size pin (8→10) was the only failure.

### Deviations from the plan text
- Migration is **V041**, not V039 — V039 (platform_settings) and V040 (flywheel seed) already existed.
- Plan says "yml" — payment-executor uses `application.properties`; property added there matching the NEPAL style.
- `lookupStatus` note: NEPAL's corridor service does not probe, but the SendMN adapter returns in-body UNKNOWN (never throws on ambiguity), so `SendmnPaymentService` probes on UNKNOWN — the ADR-016-correct reading of the checklist.
- Untouched (per constraints): scheme-adapter-sendmn/ninepay, settings.gradle, simulators, smart-router service.

### Open / follow-ups
- QPay identifiers ('qpay','sendmn','mn.qpay', tag58=MN) are PLACEHOLDERS until SendMN supplies a sample QR (open issue tracked in the plan).
- `lookupStatus` reference→txTokenNo map is in-process; a restart degrades the probe to NOT_FOUND (pre-guard behaviour). A durable mapping (or adapter status-by-reference endpoint) is a hardening follow-up.
- ops-partner-bff `StubConfigRegistryClient` scheme roster was not extended (out of Phase-2 scope; no test pins it to the catalog).
- Local FailoverPaymentRouter path for scanned qpay QRs treats the wallet amount as MNT pass-through (mirrors the NEPAL/NPR pattern); the KRW→MNT FX flow only runs on the `partner=SENDMN` path.

## lookupStatus hardening

Restart-proofs the ADR-016 SENDMN status probe: the hub's in-process reference→txTokenNo map is now only the cheap first hop; the durable source of truth is the adapter's new by-reference endpoint over `smn_payments`.

### Files changed

**scheme-adapter-sendmn**
- `db/migration/V002__smn_payments_hub_reference.sql` — NEW (V001 untouched). `smn_payments.hub_reference VARCHAR(64)` + index; deliberately NOT unique (a hub retry after a lost verify-qr response can mint a second attempt row per reference).
- `SmnPaymentEntity` / `SmnPaymentRepository` — `hubReference` field + `findByHubReferenceOrderByIdDesc`.
- `VerifyQrRequest` — + `reference`; persisted at verify-qr time, i.e. committed BEFORE any Confirm can be sent (the ADR-016-correct anchor point). `SubmitMpmRequest` — + `reference` as backfill only (set iff still null).
- `SendmnSchemeAdapter` — persists reference at verify; NEW `statusByReference(reference)`: resolve → fresh PaymentStatus poll (same `applyPolledStatus` as the token endpoint) → same `StatusResponse`; multi-attempt references prefer the APPROVED row (money moved) else newest; unknown → `PAYMENT_NOT_FOUND`.
- `SendmnSchemeController` — NEW `GET /internal/scheme/sendmn/status/by-reference/{reference}`; unknown reference → 404 structured `ApiError` via existing `ApiExceptionHandler`.

**payment-executor**
- `SendmnRestSchemeClient` — sends `txnRef` as `reference` on verify-qr (durable leg) and submit-mpm (backfill); `lookupStatus` map miss now falls back to by-reference instead of concluding NOT_FOUND. Semantics: only a TRUE 404 (adapter never saw the reference → no Confirm possible) maps to NOT_FOUND, mirroring `NepalRestSchemeClient`; any transport/5xx failure on either probe holds PENDING (never NOT_FOUND). Known-token path unchanged.

**lib-errors** (shared)
- `TraceNames.PORT_NAMES` — + 8093 sendmn-adapter, 8096 ninepay-adapter, 9106+9108 sim-sendmn (compose/fleet split; fleet 9106 = sim-nepal-qr), 9107 sim-ninepay.

### Tests
NEW `SendmnSchemeControllerTest` (by-reference 200 + 404 ApiError body); `SendmnSchemeAdapterTest` +4 (reference persisted at verify, by-reference happy/unknown/APPROVED-preference, submit backfill); `SmnPersistenceH2SliceTest` +1 (V002 column + newest-first lookup — migration coverage); `SendmnRestSchemeClientTest` +2 and 1 rewritten (map-miss→by-reference fallback simulating restart; by-reference 5xx holds PENDING; map-miss now requires the adapter's 404 before NOT_FOUND).

`gradlew :services:scheme-adapter-sendmn:test :services:payment-executor:test :libs:lib-errors:test` → **BUILD SUCCESSFUL** first run (sendmn-adapter 51, payment-executor 196, lib-errors 84 tests; 0 failures).

### Remaining
- The probe is now durable at the adapter; if the ADAPTER's DB is lost, by-reference 404s and behaviour degrades to pre-guard fail-over (accepted — same trust boundary as `smn_payments` itself).
