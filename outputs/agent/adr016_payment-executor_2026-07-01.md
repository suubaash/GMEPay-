> 작업: ADR-016 payment-executor failover router / 출처: agent

# ADR-016 — QR-classified failover routing (payment-executor)

Branch `fo/payment-executor` (off `fo/contracts`). Scope: `services/payment-executor/` only;
libs + other services untouched. Full suite green: **128 tests, 0 failures, 0 errors, 0 skipped**
(`./gradlew :services:payment-executor:test`).

## 1. QrSchemeClassifier (`domain/QrSchemeClassifier.java`)
Parses a scanned QR → `Classification{networkIdentifier, country, mode}` (ADR-016 §1).
- **EMVCo**: walks TLV triples; reads Merchant Account Information templates (tags 26–51),
  sub-tag `00` = network GUID/AID (`com.zeropay`, `fonepay.com`, NepalPay GUID…); country from
  tag `58`; mode = MPM for a scanned merchant QR. Defensive TLV walk (malformed → stop, best-effort).
- **JSON QRs** (Khalti/mobank) classified by shape → synthetic network (`khalti`/`mobank`).
- Substring-marker fallback keeps `com.zeropay`/`fonepay.com`/`nepalpay`/`khalti` routable from a
  slightly non-conformant QR.
- The network id is the deterministic routing key; country/mode are filter context.

## 2. SmartRouterClient (`domain/client/SmartRouterClient.java` + `client/rest/*`)
`resolve(network, country, mode, direction)` → ordered `List<PartnerSchemeView>` (priority asc).
- `RestSmartRouterClient` — `GET {base}/v1/route/resolve?network=&country=&mode=&direction=`,
  gated `@ConditionalOnProperty(prefix=gmepay.smart-router, name=base-url)`. Resolve failure →
  empty list (caller → SCHEME_UNAVAILABLE), not a 500.
- `FixtureSmartRouterClient` — `@Primary @ConditionalOnMissingBean(RestSmartRouterClient.class)`
  in-process fallback for tests / no-router sandbox: `com.zeropay`→zeropay(1 cand),
  fonepay/nepalpay/khalti→NEPAL(1 cand). A single candidate = pre-ADR-016 direct dispatch.
- `PartnerSchemeView.schemeId` maps straight to the code `SchemeClientRouter` dispatches on.

## 3. lookupStatus (anti-double-charge guard, ADR-016 §4)
Added `SchemeClient.lookupStatus(schemeId, reference)` → `APPROVED|PENDING|REJECTED|NOT_FOUND`
(default `NOT_FOUND`). Implemented in:
- `NepalRestSchemeClient` — `GET /internal/scheme/nepal/status?reference=` (404 → NOT_FOUND).
- `RestSchemeClient` — `GET /internal/scheme/zeropay/status?reference=` (404 → NOT_FOUND).
- `SchemeClientRouter` — routes the probe by schemeId. Unknown/unreachable → best-effort NOT_FOUND.

## 4. FailoverPaymentRouter (`domain/FailoverPaymentRouter.java`, ADR-016 §3–4)
classify → resolve ordered candidates → walk bounded by `gmepay.routing.max-hops` (default 3):
- **APPROVED** → done, records txn (resilient) + attempt.
- **Business decline** (`invalid_qr / unsupported_qr / receiver_not_found /
  receiver_not_eligible / insufficient / duplicate_reference`, matched normalised/contains) →
  **TERMINAL, no failover, no lookup**.
- **Technical failure** (`SchemeTimeoutException` / `PaymentException` / non-business decline
  code) → `lookupStatus(candidate, reference)`; **APPROVED/PENDING → return that, NO second
  submit** (anti-double-charge); NOT_FOUND/REJECTED → fail over to next candidate.
- All exhausted → SCHEME_UNAVAILABLE.
Distinguishes business-vs-technical from the canonical exception/code the adapters already raise
(`SchemeDeclinedException` w/ business code vs. timeout/transport). Every attempt
(partner/outcome/reason) written to `ExecutionAttemptRepository` (resilient try/catch).

## 5. WalletPayController wiring
Retired `NepalQrDetector` branch → classify QR; a **known non-ZeroPay network** dispatches through
`FailoverPaymentRouter` (subsumes Nepal). ZeroPay (`com.zeropay`/`5802KR`) + SENDMN keep the
unchanged GMEREMIT/SENDMN services (merchant validation + fee preserved exactly). Single-candidate
ZeroPay resolution = today's behaviour.

## NepalQrDetector retired?
**Yes** — `NepalQrDetector` + `NepalQrDetectorTest` deleted (`git rm`). Nepal string-match cases
subsumed by the classifier. `NepalPaymentService` left in place as a standalone bean (no longer on
`/v1/pay`); not deleted to keep the change minimal (out of ADR-016's named scope).

## Test status
- `QrSchemeClassifierTest` (5): fonepay.com / com.zeropay / khalti-JSON / country tag / unknown.
- `FailoverPaymentRouterTest` (7): (a) tech-fail+NOT_FOUND→secondary APPROVED; (b) business-decline
  →terminal, secondary NOT tried; **(c) timeout+lookup APPROVED→returns primary, exactly ONE
  submit (no double-charge)**; (d) single ZeroPay→APPROVED; +no-candidates, +all-exhausted,
  +schemeId carried.
- `NepalRestSchemeClientTest` / `RestSchemeClientTest` (+2 each): lookupStatus APPROVED + 404.
- `WalletPayControllerTest` (9): Fonepay→failover; ZeroPay→GMEREMIT unchanged; existing cases green.

## Remaining / deferred
1. `NepalPaymentService` still present as a dead-ish bean (not on `/v1/pay`); a follow-up could
   fold it fully into the Nepal adapter or delete it.
2. FX for cross-border failover payments: `FailoverPaymentRouter` passes the wallet amount through
   in the scheme's local currency (KRW/NPR) with no KRW→local conversion (same TODO(fx) as the old
   Nepal path).
3. Circuit-breaker skip of open-circuit candidates (ADR-016 §4 phase-2) not implemented.
