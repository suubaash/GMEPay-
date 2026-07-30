# payment-executor — CHANGELOG

All notable changes to the payment-executor service. Newest first.

## [feat/exec-gap-closure-2026-07-28] - 2026-07-28 (the POISON trap in the revenue-posting replay: T2-5 follow-up / T3-12)

Flyway **V012** (widens `ck_ledger_ops_runs_job` — additive, cannot fail on existing data).

### Fixed - HTTP 406 no longer buries a replay row on the first sweep
`RestRevenuePostingReplayClient.isRetryableStatus` was `>= 500 || 408 || 429`, so a 406 became a
`permanentRejection` and `RevenuePostingReplayService` called `row.poison(..)` on the **first** sweep.
POISON is terminal by design and there was no requeue path anywhere in the codebase. That turned
T3-12 — revenue-ledger returning 406 on two journal endpoints because of a *server-side*
content-negotiation defect — into permanently orphaned booked revenue, recoverable only by editing
`revenue_posting_failures` in production by hand.

- **406 is now retryable, and so are 404, 405 and 415.** The boundary is a real one rather than a
  convenient one: these four are the codes Spring MVC raises from **routing and content negotiation**
  (`NoHandlerFound`, `MethodNotSupported`, `MediaTypeNotSupported`, `MediaTypeNotAcceptable`), all
  *before* the handler method reads the body. They are statements about which code is deployed, not
  verdicts on the payload — and between two services we deploy ourselves over a contract we own, they
  can only mean a version mismatch, which is exactly what a retry after a deploy fixes.
- **This is not "retry everything".** 400/409/422 still terminate on the first sweep — the server read
  the body and refused it, so the fast alert is the right answer. 401/403 also still terminate: they
  are a credential verdict, and re-presenting rejected credentials on a schedule is a bad pattern
  regardless of whether the row survives it. Both directions are pinned by test.
- **Retryable is still bounded.** The eight-attempt exponential budget is unchanged, so a genuinely
  permanent 406 still reaches POISON — after a deploy window (~2 h) instead of within one sweep.

### Added - `POST /internal/ops/revenue-posting-failures/requeue`
The general escape hatch, because no status classification will be right about every future server
defect: retryability buys hours, a defect found a week later needs this.

- Moves **POISON** rows back to `PENDING` with `attempts=0` and `next_attempt_at=now()`, targetable by
  `postingTypes` and/or `ids` — the immediate need being
  `['ROUNDING_RESIDUAL','REVERSAL_JOURNAL']`. It does **not** replay: requeue makes rows due, the
  sweep sends them, so an operator can inspect what became PENDING before triggering `POST /replay`.
- `attempts` resets to 0 rather than being preserved: leaving it at the exhausted value would poison
  the row again on the very next sweep, which is a no-op dressed as a fix.
- **Idempotent** — only POISON is selected, so a repeat requeues 0 rows and still answers 200. A
  nervous operator running the command twice is a no-op, not a corruption.
- **Audited** to `ledger_ops_runs` as `REVENUE_POSTING_REQUEUE` / `OPERATOR` / `X-Operator-Id`
  (hence V012). Load-bearing rather than decorative: resetting `attempts` discards the row's own
  record of how many times the posting was pushed at the ledger, so this row is where that history
  and the *why* survive. Unlike the other three jobs it is never scheduled.
- **Internal-auth gated** by the wholesale `/internal/**` rule in `SandboxSurfaceInternalAuthConfig`;
  401 without the token is asserted over real HTTP, because the gate is a servlet filter a slice test
  never runs and the endpoint re-arms money postings.
- **Refused rather than guessed.** An unfiltered requeue (`{}`) is **400** — "requeue everything" is a
  much larger decision than "requeue what the 406 broke", and an empty body must not silently mean the
  larger one. A mistyped posting type is **400** too, never a silent `requeued=0` that would let the
  operator believe the backlog was already clear.
- Rows poisoned as structurally unreplayable (no payload was ever captured) are **skipped and
  counted**, not requeued into a budget they cannot survive. A bulk type filter never touches
  `ABANDONED` — that is a human's judgement, reversible only by naming the id.

## [feat/exec-gap-closure-2026-07-28] - 2026-07-28 (T4-1: the Nepal corridor gets a real money path)

### Fixed - the Nepal corridor stopped sending KRW as NPR
`NepalPaymentService` used to hand the wallet's KRW amount to the Nepal adapter labelled `NPR`: no FX,
no fee, no prefunding, no revenue. Its own Javadoc said so ("the wallet-labeled KRW must not be sent
as NPR in production"). It now runs the same pipeline `SendmnPaymentService` runs for KRW->MNT:
price -> FX -> fee -> USD prefunding debit -> scheme submit -> transaction commit -> revenue capture.

- **FX applied, both directions.** `offerRate = liveMid(KRW/NPR) x (1 - configuredMargin)`.
  A KRW-quoted request derives the NPR payout; an NPR-quoted request derives the KRW collection.
  There is no pass-through mode left, and an amount in any other currency is rejected rather than
  reinterpreted.
- **Configured service fee.** `chargedKrw = amountKrw + feeKrw`.
- **Prefunding.** `chargedUsd` is debited exactly once on `partnerTxnRef` and REVERSED on a scheme
  decline / non-APPROVED status. A `PENDING` (or unresolvable) outcome KEEPS the float - the payment
  may have landed. A transport failure now runs the idempotent `lookupStatus` probe before deciding
  (ADR-016 SS4) instead of the old blind path.
- **Revenue booked for real.** `postRevenueCapture` (FX margin + service charge), never
  `postRoundingResidual` - corridor P&L must not land in `REVENUE_ROUNDING` (the T2-1 mistake).
  Failed postings go to the durable `revenue_posting_failures` sink.
- **Transaction values are real, not null.** The APPROVED `StatusPatch` carries `payoutMarginUsd`,
  `collectionMarginUsd`, `collectionUsd` and `prefundDeductedUsd`; the row records the NPR payout leg
  and the KRW collection leg.

### Added - pricing is configuration, and an unpriced corridor REFUSES
- **`NepalCorridorPricing`** resolves the rate, margin and fee. The margin and fee are business
  decisions, so they are read from config and **never defaulted**:
  - margin <- config-registry `GET /v1/partners/{code}/fx-config` -> `marginBps` (`partner_fx_config`, V019)
  - fee <- config-registry `GET /v1/partners/{code}/fee-schedules/effective?schemeId=NEPAL&direction=OVERSEAS&amountUsd=...`
    -> `serviceFeeUsd` (`partner_fee_schedule`, V018)
  - or module config `gmepay.payment.nepal.fx-margin` / `.service-fee-krw`, both **unset by default**
    (so a local/sim environment is made transactable by CONFIGURATION, not by a hardcoded default).
- **`CorridorPricingUnavailableException`** -> HTTP 503 with a stable code:
  `CORRIDOR_PRICING_NOT_CONFIGURED` (retryable=false - an owner must enter the terms) or
  `CORRIDOR_RATE_UNAVAILABLE` (retryable=true). Every refusal happens **before** any side effect: no
  float moved, no scheme call, a FAILED attempt row persisted.
- **The 1350 KRW/USD fallback is NOT used to price.** `UsdAmountBasis.KRW_PER_USD_FALLBACK` exists so
  a regulatory CAP can still be evaluated during a rate outage (a conservative direction for a
  control); using it to sell FX is the opposite. Nepal fetches USD/KRW itself and fails closed.
- **`PartnerConfigClient.resolveFxConfig` / `.resolveServiceFeeUsd`** + their `RestPartnerConfigClient`
  implementations. Both stay fail-soft (empty on 404/unreachable) - the DECISION to refuse lives in the
  corridor, preserving the "a client never fails a payment by itself" contract.
- **`WalletResult.approvedFxInCurrency`** - an approval that both applied FX and paid out in a non-KRW
  currency (`approvedFx` predates `payCurrency`; `approvedInCurrency` predates FX).

### Changed
- **`FailoverPaymentRouter` DELEGATES the Nepal corridor** to `NepalPaymentService` instead of walking
  it in the generic candidate loop. A dispatcher cannot price a corridor, and walking it was exactly
  how the KRW arrived labelled NPR. `NepalPaymentService` is therefore no longer dead code - it is the
  corridor's single money path, and there are no longer two divergent Nepal paths. Nepal does not fail
  over (one scheme edge; a "failover" would pay a different corridor with Nepal's pricing applied), and
  extra candidates are logged, not walked. **With no Nepal money path wired the router REFUSES** - the
  pass-through is not an acceptable fallback.
- **`WalletPayController`** - `payAmount` now reports the FX'd payout (not the KRW leg) whenever a
  corridor applied FX; a pricing refusal feeds the DECLINE_SPIKE monitor like any other decline.
- **T4-2 basis on this path got stronger**: the gate runs `enforceUsd(chargedUsd)` - bit-for-bit the
  figure the prefunding debit moves - instead of converting a pass-through NPR amount at USD/NPR.

### Tests
- **`NepalPaymentServiceTest`** (24): FX applied / never pass-through, offer rate = mid - margin,
  NPR-quoted derivation, fee applied, prefunding deducted once + reversed on decline + KEPT on PENDING,
  revenue to the real accounts with `postRoundingResidual` never called, real margins on the commit,
  both legs recorded, config-registry-sourced pricing, and six fail-closed refusals (no margin, no fee,
  no KRW/NPR rate, no USD/KRW rate, nonsense margin, no float ledger) each asserting no scheme call and
  no float moved - plus T4-2 gating still enforced on the corridor.
- **`FailoverPaymentRouterTest`** - Nepal delegation + refusal-without-a-money-path. The generic
  failover-mechanics candidate moved off `NEPAL` (it is delegated now, so it can no longer stand in for
  "some cross-border scheme"); same for `ResilientFailoverIntegrationTest`.
- **`WalletLimitEnforcementTest`** - the Nepal block now exercises the real production chain
  (`/v1/pay` -> router -> corridor -> gate) on KRW amounts and asserts the cap is charged on the
  corridor's fee-inclusive `chargedUsd`.

### Owner action required before the corridor can transact
The FX margin and the service fee for KRW->NPR. Until they are configured the corridor refuses every
payment - deliberately not sellable rather than silently mispriced.

## [feat/pay-idempotency] — 2026-07-03 (request-level idempotency on POST /v1/pay)

### Added
- **Stripe-style request idempotency on the wallet payment endpoint** (`WalletPayController`
  `POST /v1/pay`), so a client retry (network timeout, double-tap) NEVER creates a duplicate payment.
  Revives the previously-DEAD `idempotency_keys` table (Flyway V002) + `IdempotencyRecordEntity` /
  `IdempotencyRecordRepository` — no new table, migration, or dependency. Purely additive; the money
  model, two-phase authorize/confirm, anti-double-charge guard, and business-decline handling are
  unchanged.
- **Optional `Idempotency-Key` header** (also accepts `X-Idempotency-Key`). **Absent → behaviour is
  byte-for-byte identical to before** (the existing `partner_txn_ref` dedup still applies); the key is
  not required.
- **Insert-first concurrency claim** over `UNIQUE(partner_id, idempotency_key)`: the first request
  inserts a claim row (`request_hash`, `created_at`, `expires_at = now+24h`, no response yet), executes
  the existing `pay()` logic exactly once, then records `response_status + response_body (serialized
  WalletPaymentResponse JSON) + txn_ref` onto the row.
  - Duplicate-key collision with a **different `request_hash`** → **422 `idempotency_key_reuse`** (same
    key reused for a different payload — a client bug we refuse to mis-serve).
  - Collision, **same hash + recorded response** → **verbatim REPLAY** (same HTTP status + body) with
    ZERO side effects (no second scheme submit / txn / ledger entry).
  - Collision, **same hash but no response yet** (concurrent in-flight first request) → **409
    `idempotency_in_progress`**; the caller retries shortly. Not executed.
- **`request_hash`** = SHA-256 over a canonical serialization of the payment-defining fields
  (`qrPayload`, amount, resolved currency, partner, userRef).
- **Server-error key-poisoning decision**: if the first execution throws (5xx), the claim row is
  **deleted** so the key is NOT poisoned and a genuine retry can re-claim and execute. Only a completed
  outcome (201 APPROVED / 422 business DECLINE) finalises the key for replay. (Chosen as the safer of
  the two options — a transient server error must never permanently block the payment.)
- **Partner-id resolution** reuses the well-known sandbox constants (`GMEREMIT=1` — matching the
  `X-Partner-Id` default used by `PaymentController`/`BalanceController` — and the existing
  `SENDMN_PARTNER_ID=2`); any other alias derives a STABLE positive id from the upper-cased alias hash,
  so keys stay partner-scoped without a config-registry round-trip.
- **Tests** (`WalletPayControllerTest`): same key+body → executed once + replay (asserts exactly one
  `gmeremitPaymentService.pay`); same key+different body → 422; concurrent claim with no stored
  response → 409; no header → unchanged, store untouched. Full suite: **173 passing, 0 failures**.

## [feat/resilience-scheme-breaker] — 2026-07-03 (per-scheme circuit breaker + bulkhead on the scheme edge)

### Added
- **Production-grade resilience on the OUTBOUND scheme calls** — a per-scheme **circuit breaker +
  bulkhead (semaphore)** plus a hard **synchronous call timeout**, so a dead or slow QR scheme fails
  fast and triggers failover instead of hanging/hammering the adapter. Purely additive; the money
  model, two-phase authorize/confirm, anti-double-charge guard, and business-decline terminality are
  unchanged.
- **`ResilientSchemeClient`** (new `@Primary` `SchemeClient`) decorates `SchemeClientRouter`. Every
  `submitMpm / submitCpm / lookupStatus / checkBalance` is wrapped by a breaker + bulkhead **keyed on
  the scheme id** (instance name = schemeId, e.g. `NEPAL`, `ZEROPAY`), obtained programmatically from
  `CircuitBreakerRegistry` / `BulkheadRegistry` (the scheme id is dynamic, so no blanket annotations).
  One dead scheme does NOT trip the others.
- **Failover contract preserved**: an OPEN breaker (`CallNotPermittedException`) or a saturated
  bulkhead (`BulkheadFullException`) is translated to `SchemeTimeoutException` (a `PaymentException`),
  the exact technical-failure type `FailoverPaymentRouter` already fails over on — never a business
  decline, never a success, never a hard 500 to the wallet. An open breaker short-circuits BEFORE any
  HTTP call, so no charge occurs and no double-charge is possible.
- **`SchemeFailureRecordPredicate`** — keeps authoritative `SchemeDeclinedException` business declines
  OUT of the breaker's failure tally (a healthy scheme correctly declining bad QRs must not trip);
  timeouts / 5xx / connect failures still count.
- **Synchronous call timeout** on the scheme `RestClient`s (`RestSchemeClient` / `NepalRestSchemeClient`):
  connect ~2s / read ~5s via `ClientHttpRequestFactorySettings`, so a hung socket aborts as
  `SchemeTimeoutException` rather than hanging forever. (resilience4j `TimeLimiter` intentionally NOT
  used — these calls are synchronous, not `CompletableFuture`-based.)
- **Dependency**: `io.github.resilience4j:resilience4j-spring-boot3:2.2.0` (matches Spring Boot 3.3.x).
- **Config** (`application.properties`, all override-able): breaker sliding-window 10, failure-rate 50%,
  wait-in-open 10s, half-open permitted 3; bulkhead max-concurrent 16 per scheme; RestClient connect
  2000ms / read 5000ms.
- **Tests**: `ResilientSchemeClientTest` (breaker opens then short-circuits; open→`SchemeTimeoutException`;
  per-scheme isolation; business declines don't trip; healthy pass-through) and
  `ResilientFailoverIntegrationTest` (open primary breaker → fails over to healthy secondary and
  approves; open breaker never approves and never delegates a submit; secondary scheme unaffected).

## [feat/e2e-runner-be] — 2026-07-03 (sandbox E2E payment test runner)

### Added
- **Sandbox End-to-End (E2E) payment test runner** — drives the REAL payment journey over loopback
  HTTP against this service's own `POST /v1/pay/classify` + `POST /v1/pay`, and persists each run +
  ordered steps for later review. Purely additive; no existing endpoint changed.
- **New API, base path `/v1/sandbox/e2e`** (`SandboxE2eController`):
  - `GET /options` → `{countries:[{code,label,currency}], partners:[{code,label}], mpmTypes:["STATIC","DYNAMIC"]}`
    (countries NP/Nepal/NPR, KR/Korea/KRW; partners GMEREMIT, SENDMN).
  - `POST /run` body `{country,partner,amount,mpmType}` → 200 `RunDetail` (summary + `steps`); persists the run.
  - `GET /runs?limit=50` → newest-first `[RunSummary]`.
  - `GET /runs/{id}` → `RunDetail` (404 when absent).
- **`E2eRunner`** — currency NP→NPR / KR→KRW; persists the run FIRST (id → `userRef=e2e-<id>`), then
  executes 4 ordered steps, each recording PASS/FAIL/SKIP + a human detail + `latencyMs` + `httpStatus`,
  stopping at the first FAIL (remaining SKIP): **Resolve QR** (picks the (country,mpmType) QR; DYNAMIC
  injects an EMVCo tag-54 amount + a recomputed tag-63 CRC), **Classify** (PASS iff `supported && currency==expected`),
  **Pay** (PASS iff HTTP 201 && `status==APPROVED`), **Verify receipt** (PASS iff a non-blank `schemeTxnRef`).
  Overall status FAIL if any step failed; `failedStep` = first failed step name.
- **`SelfPayClient`** — loopback HTTP client to `${gmepay.self.base-url:http://localhost:8080}`; unlike the
  other adapters it does NOT throw on non-2xx (the runner needs a 422 decline's status + raw body).
- **`Crc16Ccitt`** — CRC-16/CCITT-FALSE (poly `0x1021`, init `0xFFFF`) for the DYNAMIC tag-63 checksum;
  **`SandboxQrCatalog`** holds the NP/KR static QRs + the DYNAMIC builder.
- **Flyway `V004__create_sandbox_e2e.sql`** — `sandbox_e2e_run` + `sandbox_e2e_step` (portable DDL:
  `BIGSERIAL`/`TEXT`/`NUMERIC`/`TIMESTAMP WITH TIME ZONE`, applies on both PostgreSQL and the H2 slice).
  New JPA entities `SandboxE2eRunEntity`/`SandboxE2eStepEntity` + `SandboxE2eRunRepository`.

### Tests
- `E2eRunnerTest` (4): classify+pay-approved → PASS with 4 PASS steps; pay-declined → FAIL, failedStep=Pay,
  Verify SKIP; classify currency-mismatch → FAIL at Classify; DYNAMIC CRC round-trips through the real
  `QrSchemeClassifier`. `SandboxE2eControllerTest` (3): options catalog, run JSON shape, 404 on missing run.
  Full module suite green.

## [feat/pay-currency] — 2026-07-02 (wallet /v1/pay accepts a pay currency; Nepal executes in NPR)

### Added
- **Optional `currency` on `POST /v1/pay`** (`WalletPaymentRequest.currency`, ISO-4217, default
  **`KRW`** when absent — full back-compat). The existing `amountKrw` value is interpreted as the
  amount in `currency` (field name kept for wire compatibility). New `payCurrency()` accessor resolves
  the value (upper-cased, KRW fallback) and validates it as a 3-letter code.
- **Cross-border pay currency threaded through the failover route.** `WalletPayController` passes
  `req.payCurrency()` into a new `FailoverPaymentRouter.pay(qr, amount, userRef, direction, payCurrency)`
  overload; the wallet-supplied currency is authoritative (falls back to the scheme-derived currency
  when null via `resolveCurrency`). A **Nepal (Fonepay) scan with `currency=NPR`** now submits the
  amount to `scheme-adapter-nepal` **as NPR** (the adapter converts NPR→paisa) instead of assuming KRW,
  and records the transaction as OVERSEAS/NPR.
- **Response reflects the pay currency.** `WalletPaymentResponse` gains additive `payCurrency` +
  `payAmount` (`@JsonInclude(NON_NULL)`), populated for a non-KRW scheme via the new
  `WalletResult.approvedInCurrency(...)` factory. The domestic KRW path leaves them null → its response
  shape is byte-for-byte unchanged.

### Unchanged
- **ZeroPay / GMEREMIT domestic path** (currency absent or `KRW`): identical routing, amount treated as
  KRW, ₩500 fixed fee, no `payCurrency`/`payAmount` in the response. No KRW→foreign FX is performed here
  (the wallet computes the KRW debit; corridor FX via rate-fx is a separate item).
- The 4-arg `FailoverPaymentRouter.pay(...)` overload is retained (delegates with `payCurrency=null`),
  so existing callers/tests are unaffected.

## [fix/payment-executor] — 2026-07-02 (harden kill-switch: fail-CLOSED for security + DECLINE_SPIKE)

### Fixed
- **Defect #4 — kill-switch safety inversion (fail-OPEN → fail-CLOSED for security).** The operational
  gate is a kill switch, but `RestOperationalStatusClient` defaulted to fail-**OPEN** on an unreachable
  config-registry with no client timeout: a cold executor (no cached value) during a config-registry
  outage would let a **suspended/paused partner transact**, defeating the switch. Fix: the default is now
  fail-**CLOSED for the security flags** (`systemPaused` / `maintenanceMode` / partner/scheme/route
  *suspended*) — when status is unreachable/unknown and there is no fresh or last-known-good cached value,
  the client returns a synthetic `systemPaused` status so the gate DENIES the new authorization with the
  existing `SYSTEM_PAUSED` code. A last-known-good cached value is still preferred over either policy, so a
  brief blip does not flip behaviour; only a genuinely no-signal cold executor fails closed. Config default
  flipped: `gmepay.ops.status.fail-open` now defaults to **`false`** (set `true` to restore legacy
  allow-on-outage). Additive; no contract/lib change.
- **Defect #4 — hard client timeout.** The `RestOperationalStatusClient` RestClient now sets explicit
  connect + read timeouts (`gmepay.ops.status.connect-timeout-millis` /
  `read-timeout-millis`, default **500ms** each) via `ClientHttpRequestFactorySettings`, so a HUNG
  config-registry can no longer stall the pay path. A timeout surfaces as `ResourceAccessException` →
  treated as unreachable → the fail-closed-security rule applies.

### Added
- **Defect #5 — `DECLINE_SPIKE` ops alert** (`alert/DeclineSpikeMonitor` + local `alert/OpsAlertEvent`).
  A lightweight in-memory rolling-window decline counter per partner AND per classified scheme/network.
  When the decline rate over `window-seconds` (default 60s) strictly exceeds `threshold-rate`
  (default 0.5) with at least `min-samples` (default 20) outcomes in-window, it emits an
  `OpsAlertPayload` (`alertType=DECLINE_SPIKE`, severity WARN / CRITICAL≥0.8, `subjectRef`=partner/scheme)
  onto `gmepay.ops.alert` via the `EventPublisher` seam (`LogEventPublisher` fallback logs it). Per-subject
  cooldown (`cooldown-seconds`, default 300s) suppresses repeats. **Default OFF**
  (`gmepay.decline-spike.enabled=true` to enable); injected `@Nullable` into `WalletPayController` so it is
  purely additive. Alerting never throws into the pay path. A `Clock` bean (`@ConditionalOnMissingBean`,
  system-UTC) was added for the monitor.

### Tests
- `RestOperationalStatusClientTest`: default fail-CLOSED on unreachable+no-cache; last-known-good cache
  preferred over the fail-closed default on a later outage; a real hung local HTTP server proves the read
  timeout fires within budget and is treated as unreachable → fail-closed.
- `OperationalGateTest`: the synthetic unreachable (`systemPaused`) status → `SYSTEM_PAUSED` denial.
- `DeclineSpikeMonitorTest`: a decline burst emits `DECLINE_SPIKE`; below-min-samples and all-approved do
  not alert; cooldown suppresses the repeat.

## [ops/payment-executor] — 2026-07-01 (Operations operational gate)

### Added
- **`OperationalGate`** (`domain/OperationalGate`) — checked at the START of every NEW payment
  authorization to refuse new work while the platform is globally paused / in maintenance, or when
  the partner / scheme / route resolved for THIS payment is individually suspended. Precedence:
  `systemPaused` → `maintenanceMode` → partner → scheme → route; the first match throws
  `OperationalGateException` with a stable canonical code. Case-insensitive, trimmed list matching;
  `null` references skip their per-entity check (partial resolution at gate time).
- **`OperationalStatusClient`** + `RestOperationalStatusClient` — reads config-registry's
  `GET /v1/ops/operational-status` → `OperationalStatusView` (lib-api-contracts). Gated
  `@ConditionalOnProperty(gmepay.config-registry.base-url)`, with a short in-memory cache
  (`gmepay.ops.status.cache-ttl-millis`, default 3000ms) so the hot pay path does not round-trip
  per payment. `FixtureOperationalStatusClient` (`@ConditionalOnMissingBean`) returns
  `OperationalStatusView.allClear()` so tests / a no-config-registry sandbox proceed.
- **Fail-open vs fail-closed** — `gmepay.ops.status.fail-open` (default **true** = fail-OPEN → allow
  when status unreachable and no cached value). A last-known-good cached value is preferred over
  either policy so a brief config-registry blip does not flip behaviour.
- **Gate hooks** — wallet `POST /v1/pay` (covers the GMEREMIT/SENDMN inbound branches AND the
  FailoverPaymentRouter outbound branch; gated by partner alias + classified network as route) and
  the orchestrated `POST /v1/payments/authorize` (gated by partner code + scheme id + direction,
  AFTER the idempotent-replay check so an in-flight replay is never gated). Confirm/capture, refund,
  cancel and status lookups are NOT gated — in-flight payments complete even mid-pause.
- **Error surfacing** — `OperationalGateException` → HTTP 503 (retryable) via `PaymentExceptionHandler`,
  emitted with the stable codes `SYSTEM_PAUSED` / `PARTNER_SUSPENDED` / `SCHEME_SUSPENDED` /
  `ROUTE_SUSPENDED` through the `ApiError(code, …)` string ctor (lib-errors is frozen).

### Integration request
- **lib-errors** does not yet carry `SYSTEM_PAUSED` / `PARTNER_SUSPENDED` / `SCHEME_SUSPENDED` /
  `ROUTE_SUSPENDED` as `ErrorCode` enum members. They are emitted as literal codes via the
  `ApiError(String code, …)` ctor for now; promote to `ErrorCode` (503, retryable) when lib-errors
  next unfreezes so the codes are centrally documented.

## [fo/payment-executor] — 2026-07-01 (ADR-016 QR-classified failover routing)

### Added
- **`QrSchemeClassifier`** — parses a scanned QR into `{networkIdentifier, country, mode}`
  (ADR-016 §1). EMVCo: reads Merchant Account Information templates (tags 26–51), sub-tag `00`
  = network GUID/AID (`com.zeropay`, `fonepay.com`, NepalPay GUID…); country from tag `58`;
  mode MPM. JSON QRs (Khalti/mobank) classified by shape. A substring-marker fallback keeps
  well-known networks routable from a slightly non-conformant QR. The QR's network identifier
  is the deterministic routing key, not the country.
- **`SmartRouterClient`** — resolves `(network, country, mode, direction)` → ordered
  `List<PartnerSchemeView>` candidates (priority order). `RestSmartRouterClient`
  (`GET {smart-router}/v1/route/resolve`) gated `@ConditionalOnProperty(gmepay.smart-router.base-url)`;
  in-process `FixtureSmartRouterClient` fallback (`@ConditionalOnMissingBean`) resolves well-known
  networks so tests / a no-router sandbox route deterministically (single candidate = pre-ADR-016
  behaviour). Candidate `schemeId` maps straight to the `SchemeClientRouter` scheme code.
- **`SchemeClient.lookupStatus(schemeId, reference)`** → `APPROVED|PENDING|REJECTED|NOT_FOUND` —
  the anti-double-charge guard (ADR-016 §4). Implemented in `NepalRestSchemeClient`
  (`GET /internal/scheme/nepal/status?reference=`) and `RestSchemeClient`
  (`GET /internal/scheme/zeropay/status?reference=`); `SchemeClientRouter` routes it by scheme code.
  Default / unknown / unreachable → best-effort `NOT_FOUND`.
- **`FailoverPaymentRouter`** — the ADR-016 §3–4 engine on the MPM scan `/v1/pay` path: classify QR
  → resolve ordered candidates → walk them (bounded by `gmepay.routing.max-hops`, default 3),
  `submitMpm(schemeId=candidate.schemeId, …)`. APPROVED → done. **Business decline**
  (`invalid_qr / unsupported_qr / receiver_not_found / receiver_not_eligible / insufficient /
  duplicate_reference`) → **TERMINAL, no failover**. **Technical failure** (timeout / 5xx /
  SCHEME_UNAVAILABLE / connect) → `lookupStatus(candidate, reference)`; APPROVED/PENDING → return
  that (NO double-charge, no second submit); else fail over. All exhausted → SCHEME_UNAVAILABLE.
  Each attempt (partner / outcome / reason) recorded in the attempt trail (resilient). Business-
  decline vs technical is distinguished from the canonical `ErrorCode`/exception the adapters already
  return (`SchemeDeclinedException` w/ business code = terminal; `PaymentException`/`SchemeTimeout`/
  non-business decline code = technical).
- **`WalletPayController`** — the retired `NepalQrDetector` branch is replaced by the
  `FailoverPaymentRouter` for scanned-QR MPM payments to a **known non-ZeroPay network** (subsumes
  Nepal: a Fonepay QR classifies to `fonepay.com` → Nepal candidate). ZeroPay QRs
  (`com.zeropay` / `5802KR`) and the SENDMN path keep the unchanged GMEREMIT/SENDMN services, so
  their merchant validation + fee behaviour is preserved exactly. `FailoverPaymentRouter` added to
  the primary ctor; the 2-arg test ctor still compiles (defaults it null).

### Removed
- **`NepalQrDetector`** (+ its test) — retired per ADR-016. Its Nepal string-match cases are
  subsumed by `QrSchemeClassifier` (Nepal GUIDs → `fonepay.com`/`nepalpay`/`khalti`).
  (`NepalPaymentService` remains as a standalone bean but is no longer on the `/v1/pay` path —
  its Nepal dispatch is now the failover router's single Nepal candidate.)

### Tests
- `QrSchemeClassifierTest` (fonepay.com / com.zeropay / khalti-JSON / country tag / unknown).
- `FailoverPaymentRouterTest`: (a) primary technical-fail + lookup NOT_FOUND → secondary APPROVED;
  (b) primary business-decline → terminal, secondary NOT tried; (c) primary timeout + lookup
  APPROVED → returns primary, **no second submit (no double-charge)**; (d) single-candidate ZeroPay
  → unchanged APPROVED; plus no-candidates and all-exhausted.
- `lookupStatus` cases added to `NepalRestSchemeClientTest` / `RestSchemeClientTest`
  (`MockRestServiceServer`). `WalletPayControllerTest` updated: Fonepay QR → failover router;
  ZeroPay QR still → GMEREMIT. Full suite green (128 tests, 0 failures).

## [fix/wallet-nepal-routing] — 2026-07-01 (wallet /v1/pay Nepal QR routing)

### Fixed
- **Nepal Fonepay QR paid via the wallet returned DECLINED / HUB_ERROR.** `WalletPayController`
  dispatched `POST /v1/pay` ONLY by `partner`, so a Fonepay QR (which arrives as
  `partner=GMEREMIT`) went down the ZeroPay domestic path — ZeroPay merchant-lookup 404'd /
  couldn't parse it. Nepal is now decided by the QR content, not the partner.

### Added
- **`NepalQrDetector`** — `isNepal(qrPayload)` flags a Nepal QR from any marker: `fonepay.com`,
  EMVCo country tag `5802NP`, or `khalti`/`nepalpay`/`npqr` (case-insensitive). Does not misfire
  on ZeroPay QRs (`com.zeropay` / `5802KR`). Unit-tested with the sample Fonepay + ZeroPay QRs.
- **`NepalPaymentService.pay(qrPayload, amount, userRef)`** — mirrors `GmeremitPaymentService`'s
  shape/`WalletResult` but builds an `MpmSubmitRequest` with `schemeId="NEPAL"` and submits via
  the injected `SchemeClient` (the `@Primary SchemeClientRouter`) → Nepal adapter. Skips the
  ZeroPay `merchant-qr-data` validation (the adapter resolves the merchant). Treats the wallet
  amount as NPR for now (adapter → paisa ×100 HALF_UP); TODO(fx) KRW→NPR via rate-fx is a
  follow-up. Records the txn in transaction-mgmt (resilient); adapter decline/failure → DECLINED
  with the adapter's reason (not a generic HUB_ERROR).
- **`WalletPayController`** — routes to `NepalPaymentService` when `NepalQrDetector.isNepal(...)`,
  BEFORE the partner branch; GMEREMIT/SENDMN branches unchanged. `NepalPaymentService` added to
  the primary ctor; the 2-arg test ctor still compiles (defaults it to null).
- Tests: `NepalQrDetectorTest`; two `WalletPayControllerTest` cases (Fonepay QR → Nepal APPROVED;
  ZeroPay QR still → GMEREMIT). Full suite green.

## [na/wiring] — 2026-07-01 (NEPAL scheme-keyed adapter dispatch)

### Added
- **`SchemeClientRouter`** (now the `@Primary` `SchemeClient`): scheme-keyed dispatch.
  Reads the scheme code off each request (`submitMpm`/`submitCpm`) or the `schemeId` arg
  (`checkBalance`) and delegates — `NEPAL` → `NepalRestSchemeClient`, everything else /
  unknown / null → the ZeroPay `RestSchemeClient` (default). `cancelPayment` carries no
  scheme code and is a ZeroPay two-phase concept, so it routes to the default.
- **`NepalRestSchemeClient`**: single-phase adapter for `scheme-adapter-nepal`. Both
  MPM and CPM submit land on `POST /internal/scheme/nepal/submit` (submit = authorize+
  commit); the `{schemeTxnRef,status,amountPaisa}` response maps into the existing
  `MpmSubmitResponse`/`CpmSubmitResponse` shape (schemeApprovalCode ← status, approvedAt
  ← now). Base-url config key `gmepay.scheme-adapters.NEPAL.base-url` (default
  `http://localhost:18091`). `cancelPayment` throws (Nepal has no cancel endpoint).
- **`SchemeId`**: `NEPAL` appended as id `8` (existing 1..7 ids kept stable).
- Tests: `NepalRestSchemeClientTest` + `SchemeClientRouterTest` (`MockRestServiceServer`).

### Changed
- `RestSchemeClient` is no longer `@Primary` (the router is); its ZeroPay behaviour and
  base-url default (`gmepay.scheme-adapter-zeropay.base-url`) are unchanged.

## [w3/payment-executor] — 2026-06-30 (Wave-3 cross-service reconcile)

### Fixed
- **Prefunding CPM reserve/release URL.** `RestPrefundingClient.reserveCpm` now POSTs to prefunding's
  real `POST /internal/v1/prefunding/{partner}/reserve` and `releaseCpm` POSTs to
  `POST /internal/v1/prefunding/{partner}/release` (was the non-existent `/reservations` POST/DELETE).
  Shared `PrefundingReserveRequest`/`Response`/`ReleaseRequest` DTOs unchanged. Tests updated.
- **CPM execution now invokes reserveCpm/releaseCpm.** `PaymentOrchestrator.executeCpm` OVERSEAS path
  RESERVES via the canonical idempotent `reserveCpm` (idempotencyKey == txnRef) at authorize and
  RELEASES via `releaseCpm` on scheme decline, replacing the txnRef-keyed `reserve`/`release` on the
  CPM path (capture-on-success still uses the txnRef `capture` — no captureCpm exists). They were
  bound + unit-tested but never called.
- **schemeId code→numeric.** New `SchemeId.resolve(code)` maps the carried scheme CODE (e.g.
  `zeropay`) to a stable 1-based numeric id off config-registry's canonical scheme roster
  (ZEROPAY=1…QRIS=7; config-registry's catalog is code-keyed with no numeric surrogate / endpoint).
  The `payment.approved` event (`PaymentController.publishApproved`) and the per-txn revenue
  capture + commission split (`confirmMpm`) now carry the resolved id instead of 0.
- **Margins on commit/create (FX1015 zero-margin).** `TransactionClient.CreateRequest` +
  `StatusPatch` extended (additive, back-compat ctors) with the rate-lock pool fields; `authorizeMpm`
  populates them on create from the locked quote (offerRateColl, crossRate, collectionUsd,
  payoutUsdCost, collection/payout margins) and `confirmMpm` carries margins + collectionUsd on the
  APPROVED commit, so transaction-mgmt persists real margins. Cost rates (costRateColl/Pay) are not
  on the quote view / authorization snapshot → sent null.

### Tests
- `RestPrefundingClientTest`: reserveCpm/releaseCpm now expect the `/internal/v1/.../reserve|release` URLs.
- `PaymentOrchestratorCpmTest`: CPM fakes assert reserveCpm/releaseCpm (txnRef reserve/release now throw).
- `SchemeIdTest`: roster mapping, suffix tolerance, UNSET fallback.

## [p2/payment-executor] — 2026-06-30 (Phase 2 cross-service wiring)

### Added
- **Canonical `payment.approved` event payload.** `PaymentEvents.PaymentApproved` now carries the
  revenue-bearing fields (collectionMarginUsd, payoutMarginUsd, serviceChargeAmount/Ccy, feeSharePct,
  partnerId, schemeId, txnRef, revenueDate) snapshotted at authorize, and maps to the canonical
  lib-api-contracts `PaymentApprovedPayload` (camelCase, money as decimal strings) via `payload()` —
  the shape revenue-ledger + notification-webhook consume. Still rides the existing `EventPublisher`
  seam (LogEventPublisher behind `@ConditionalOnMissingBean`; outbox→Kafka supersedes at integration).
  `schemeId` rides as 0 (orchestrator carries scheme CODE, mirroring the per-txn revenue capture).
- **Prefunding REST binding (IR-pe-2 + CPM reserve/release).** `PrefundingClient` +
  `RestPrefundingClient` gain `deductionHistory(code, limit)` (`GET /v1/prefunding/{code}/deductions`
  → `PrefundingDeductionHistoryView`), `reserveCpm(...)` (`POST .../reservations` with
  `PrefundingReserveRequest`/`Response`), and `releaseCpm(...)` (`DELETE .../reservations` with
  `PrefundingReleaseRequest`). All new methods are `default`-throw on the interface so hand-written
  fakes stay valid. `GET /v1/balance?include_history=true[&limit=N]` now appends `recent_deductions`
  (non-fatal: a history hiccup degrades to balance-only). Tested via `MockRestServiceServer`.
- New `MerchantNotFoundException` → canonical `ErrorCode.MERCHANT_NOT_FOUND` (404) handler.

### Changed
- **Canonical error codes (Phase 2 flip).** `GET /v1/payments/{id}` 404 now uses
  `ErrorCode.PAYMENT_NOT_FOUND` and `GET /v1/balance` LOCAL 403 uses `ErrorCode.FORBIDDEN`, replacing
  the String-literal `ApiError` workarounds. Wire `code` values unchanged.
- **Hardened the lenient fake-merchant bypass (`GmeremitPaymentService.pay`).** STRICT is now the only
  non-dev behavior: a merchant-qr-data miss/unreachable HARD-FAILS with `MerchantNotFoundException`
  instead of synthesizing an UNKNOWN merchant. Synth is gated behind the explicit dev flag
  `gmepay.payment.dev-synth-merchant` (default false); the legacy `merchant-validation=lenient` setting
  alone no longer enables it. (Legacy `merchant-validation` is still read by `SendmnPaymentService`.)
- `services/payment-executor/build.gradle`: added `implementation project(':libs:lib-api-contracts')`
  for the cross-service contract DTOs.

### Tests
- `PaymentControllerIdempotencyTest`: asserts the canonical `PaymentApprovedPayload` revenue fields.
- `RestPrefundingClientTest`: deductionHistory bind, reserveCpm bind + 402→InsufficientPrefunding,
  releaseCpm DELETE.
- `BalanceControllerTest`: `?include_history` appends `recent_deductions`; omitted otherwise.
- New `GmeremitPaymentServiceMerchantStrictTest`: strict hard-fail (no scheme call) vs dev-synth proceed.

## [agent/payment-executor] — 2026-06-30

### Added
- **5.2-T27 — GET /v1/balance prefunding balance inquiry.** New `BalanceController`
  delegating to the prefunding service via a new `PrefundingClient.balance(partnerCode)`
  seam (implemented in `RestPrefundingClient` against
  `GET /v1/prefunding/{code}/balance`; default-throws so existing fakes stay valid) —
  payment-executor owns no prefunding store. LOCAL partners get HTTP 403 `FORBIDDEN`;
  OVERSEAS get balance + `is_below_threshold`. Money serialized as decimal strings per
  MONEY_CONVENTION. Covered by `BalanceControllerTest` + `RestPrefundingClientTest`.
- **5.2-T13 / 5.6-T11 — payment lifecycle event emission.** The service now EXPOSES
  its contract events through a `lib-events` `EventPublisher` seam: `payment.approved`
  (on confirm capture+APPROVE), `payment.failed` (on scheme DECLINE at confirm), and
  `payment.cancelled` (on a successful same-day cancel). New `PaymentEvents` domain
  records (aggregateId = payment_id, money fields alongside) and an
  `EventPublisherConfig` wiring a no-infra `LogEventPublisher`
  (`@ConditionalOnMissingBean` so an outbox→Kafka publisher can supersede it at
  integration with no caller change). `PaymentControllerIdempotencyTest` now asserts
  exactly one `payment.approved` on a won claim and none on a lost claim.

- **5.2-T16 — GET /v1/payments/{id} status retrieval.** Owner-scoped lookup
  (`PaymentAuthorizationRepository.findByPaymentIdAndPartnerId`) returning a
  `PaymentDetailResponse`. A payment owned by another partner (or absent) returns
  HTTP 404 `PAYMENT_NOT_FOUND` — never 403 — so ownership is not leaked. Entity
  status is mapped to the lowercase API contract (CONFIRMED→approved, FAILED→failed,
  UNCERTAIN→uncertain, RELEASED/EXPIRED→cancelled, else pending);
  `prefund_deducted_usd` is emitted only for OVERSEAS+CONFIRMED. New
  `PaymentNotFoundException` mapped in `PaymentExceptionHandler`. Covered by
  `GetPaymentControllerTest`.
