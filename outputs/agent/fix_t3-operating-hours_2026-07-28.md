> 작업: T3-6 operating hours / 출처: agent

# T3-6 — the seeded operating hours finally have a consumer

Closes **T3-6** in `Documentation/GAP_REGISTER.md`; CPO audit finding **P12**
(`outputs/agent/audit_cpo-product_2026-07-28.md` §P12). Reuses the three-verdict philosophy the
settlement `BusinessCalendar` established for T3-4
(`outputs/agent/fix_t3-batch-and-charts_2026-07-28.md` §2.4).

**Constraints honoured.** Only `libs/lib-api-contracts`, `libs/lib-errors`, `services/config-registry`,
`services/payment-executor` and `services/smart-router` were touched. `services/settlement-reconciliation`
was **not** touched (see §1 — the `BusinessCalendar` promotion decision), and neither was `apps/**`,
`deploy/helm/**`, `docker-compose.yml` or `scripts/**`. **No Flyway migration was added**: V024 already
exists and is already seeded — the entire gap was the absence of a consumer, so adding DDL would have been
solving the wrong problem. No server, container or Docker daemon was started.

---

## 1. Decision first: `BusinessCalendar` is NOT promoted, and that is the right call

The brief asked me to decide, and to explain. **I did not promote it.**

The T3-4 report lists the promotion as a follow-up because the class is duplicated across
`settlement-reconciliation` and `scheme-adapter-zeropay`. **`scheme-adapter-zeropay` is outside my allowed
file set.** A promotion I could actually complete would have to delete both copies and repoint both
services; what I *could* have done instead is add a third copy in `libs/`, leaving two un-migrated
services behind and breaking the guard that asserts the two existing copies stay logically identical. That
is strictly worse than the duplication it claims to fix — a half-promotion is how a codebase ends up with
three sources of truth instead of two.

There is also a substantive reason beyond scope. `BusinessCalendar` answers **"is this DATE a business
day?"** from operator-supplied holiday configuration. T3-6 asks **"is this scheme accepting traffic at this
INSTANT, in the scheme's own timezone?"** from migration-seeded weekly reference data. The two share a
*philosophy* (three verdicts, missing data is its own answer) but not a signature, not a data source and
not a unit of time. Forcing one type to serve both would have meant either a date-only API that cannot
express 18:00–22:00 Asia/Seoul, or a `BusinessCalendar` that grows a timezone and a time-of-day and stops
being a calendar.

**What I did instead** is reuse the philosophy exactly, in a purpose-built pure type placed in `libs/` so
it is shared from day one rather than duplicated and promoted later:

| T3-4 (settlement) | T3-6 (this work) |
|---|---|
| `BusinessDayVerdict` = BUSINESS_DAY / NON_BUSINESS_DAY / **UNVERIFIED** | `SchemeAvailabilityVerdict` = OPEN / CLOSED / **UNVERIFIED** |
| empty calendar ⇒ UNVERIFIED, never BUSINESS_DAY | no row ⇒ UNVERIFIED, never OPEN |
| UNVERIFIED proceeds + `BATCH_CALENDAR_UNVERIFIED` WARN, de-duped per business date | UNVERIFIED proceeds + `SCHEME_HOURS_UNVERIFIED` WARN, de-duped per (scheme, UTC date) |
| lives in the service (duplicated, pending promotion) | lives in `libs/lib-api-contracts` from the start |

`SchemeAvailability` / `SchemeAvailabilityVerdict` sit next to `SchemeOperatingHoursView`, which was
already the shared DTO for these rows — so the type went where its data already lived, and both consuming
services get the identical evaluation with zero duplication. It is pure `java.time` with no Spring and no
Jackson on the evaluation path, so it is unit-tested without a network, a container or a clock stub.

---

## 2. What was actually true before

Verified against the code, not taken from the audit.

| Claim | Verified | Detail |
|---|---|---|
| V024 seeded and readable | **Yes** | 35 rows (5 schemes × 7 weekdays); `GET /v1/admin/schemes/{id}/operating-hours` → BFF. |
| Zero consumers in payment-executor / smart-router / transaction-mgmt | **Yes** | Every `OperatingHours` hit was config-registry, `lib-api-contracts` or the BFF. Nothing on the payment path. |
| `LocationSchemeResolver` has no time branch, no `SCHEME_CLOSED` | **Yes** | Four branches: validation, no-scheme, direction, mode. `SCHEME_CLOSED` did not exist anywhere in the repo, including as a string. |
| `FixtureOperationalStatusClient.allClear()` silently no-ops | **Yes** | No log of any kind on activation. |

**Three things the audit did not say, found while working:**

1. **The only mount was `/v1/admin`.** The read the router needed lived on the *operator wizard* surface,
   the one the BFF puts behind Keycloak OIDC. So "a read path exists" was true and yet the payment path had
   no reachable consumer — it would have had to call an admin URL. Fixed by mounting the same service
   method at the flat, service-facing `/v1/schemes/{schemeId}/operating-hours`, where every other
   service-to-service config read already lives (`merchant-fees/effective`, `commission/effective`,
   `ops/operational-status`). One service method, two mappings, no duplicated projection.
2. **Only 5 of the 9 rostered schemes are seeded** — and the four gaps include **NEPAL and SENDMN, two of
   the three LIVE adapters**. This single fact decides the whole safe-default question (§4): a fail-closed
   UNVERIFIED would have taken the Nepal and Mongolia corridors down the moment this shipped.
3. **Scheme codes on the payment path are not roster-shaped.** `SchemeId` already tolerates
   `zeropay_kr`-style adapter/corridor codes. A gate that passed those straight to config-registry's
   roster-keyed URL would 404 → UNVERIFIED → **the seeded schedule would still have had no effective
   consumer for the platform's only live corridor**, while looking implemented. `SchemeId.canonicalCode`
   (the same normalisation `SchemeId.resolve` already used) is applied before the read, and a test pins it.

---

## 3. Where enforcement lives, and why there is no second entry point to forget

The gap brief's warning about T4-2 — a rule added to `/v1/payments/authorize` and not to the wallet
`/v1/pay` — shaped the design. The fix is **structural, not diligence-based**:

`OperationalGate.checkNewAuthorization(partner, scheme, route)` is the ONE method both new-payment entry
points already call. The window check is composed *into* it, so a new caller of that gate inherits both the
operator kill switch and the schedule and **cannot** re-open the split. Precedence is deliberate: the
operator hold is evaluated first — a paused platform should answer "paused", not "that rail is closed".

Four enforcement points, all before any side effect:

| Path | Where | Scheme reference used |
|---|---|---|
| `POST /v1/payments/authorize` | `OperationalGate` → `SchemeOperatingHoursGate` | the request's own `scheme_id` |
| `POST /v1/pay`, direct corridors | same gate, same call | **statically known**: `partner=GMEREMIT` ⇒ ZEROPAY, `partner=SENDMN` ⇒ SENDMN |
| `POST /v1/pay`, cross-border/failover | `FailoverPaymentRouter`, per candidate | the **resolved** scheme ids from smart-router |
| `smart-router` resolution | `LocationSchemeResolver` branch 5 | the candidate scheme ids |

The wallet path deliberately **never infers a scheme code from the QR's network identifier**. Its two direct
corridors are explicit corridor selections and are known statically; everything else is resolved first and
gated per real candidate. Guessing would have risked rejecting a payment against the *wrong* scheme's hours
— a worse failure than the gap.

Per-candidate filtering (rather than "gate the winner") matters for correctness: a corridor with a closed
priority-0 partner and an open priority-1 partner **still pays**, and a closed rail is never a failover
target. Only when EVERY candidate is closed does the payment stop.

**Not gated, on purpose:** confirm/capture, cancel and refund. `OperationalGate` already documented that
carve-out as deliberate, and it is now pinned three ways — a wallet-refund test with the gate stubbed to
reject everything, an orchestrated-confirm test with a CLOSED window, and a structural test asserting
`OperationalGate`'s only public methods are the two `checkNewAuthorization` overloads (so there is no
`checkRefund` for a future edit to call). An idempotent authorize **replay** is likewise not re-gated,
matching the existing in-flight rule.

---

## 4. Three states, and the safe default stated plainly

**UNVERIFIED PERMITS the payment.** Said without hedging: a scheme whose window cannot be established from
reference data is routed to, and the fact is recorded.

The reason is §2 finding 2. Blocking would have meant a code change taking **NEPAL and SENDMN — live
corridors carrying real money today** — offline because nobody has typed in their published hours. Missing
reference data is a data-ownership problem; converting it into a corridor outage is a worse outcome than
routing to a rail whose hours we have not recorded. This is also the call this module already makes
elsewhere: `WalletLimitGate.resolveLimits` treats "no limits row" as unconstrained (fail-open) while
refusing to *bypass* a limit it can see, and the settlement calendar proceeds on UNVERIFIED. The choice is
consistent, not novel.

What keeps it from being the silent assumption the gap is about:

1. **A row that says CLOSED is enforced without exception** — that is the whole enforcement half.
2. **Every UNVERIFIED payment logs a WARN** naming the scheme and the reason.
3. **An ops alert** (`SCHEME_HOURS_UNVERIFIED`, severity WARN) rides the existing T3-3 `OpsAlertPipeline`
   that already carries `DECLINE_SPIKE` — no new sink, no new URL to configure, no second payload shape.
   De-duplicated to one per (scheme, UTC date), because a permanently unseeded corridor emitting an alert
   per payment is an alert stream nobody reads; the per-payment evidence still exists in the WARN lines.
4. **The verdict is never inferred from absence.** `SchemeAvailability.evaluate` returns UNVERIFIED for
   every degenerate input — no rows, no row for the scheme-local weekday, an unparseable timezone, null
   times, a null instant — and there is no code path that turns any of those into OPEN.

Degradation follows the same rule: an unreachable config-registry serves last-known-good rows if it has
them, else an empty list ⇒ UNVERIFIED (permitted, logged). That is deliberately the **opposite** direction
from `RestOperationalStatusClient`, which fails CLOSED — a suspension list is a security kill switch, while
an operating window is availability reference data. Failing closed on the latter turns a config-registry
blip into a corridor outage.

---

## 5. Cutoff is not a close (task 6)

Read the seeded data before deciding, as instructed. The two columns mean different things and are **not**
collapsed:

- **ZEROPAY** is seeded `00:00:00–23:59:59 Asia/Seoul` (24x7) with `cutoff_time_local = 16:30` — the KFTC
  interbank **settlement** cutoff. Treating that as a close would shut the platform's only live corridor
  for **7.5 hours every day**. That single row is the argument.
- **BAKONG** carries `15:00 Asia/Phnom_Penh` (the NBC window close) as its cutoff, again on a 24x7 window.
- **NAPAS_247 / PROMPT_PAY / FAST_SG** carry a NULL cutoff — proof the column is optional and orthogonal to
  the window.

So: `verdict` is decided by open/close **only**; a cutoff never rejects a payment. `pastCutoff` is reported
(and logged at debug) as the settlement-eligibility fact — V024's second stated question, "which value date
does it book to?" — for a future value-date decision.

**Settlement's cutoff behaviour is unchanged.** For the record, `SettlementConfigService.DEFAULT_CUTOFF_TIME
= 16:30 Asia/Seoul` lives in **config-registry**, not settlement-reconciliation: it is the per-partner V013
settlement cutoff, a different thing from the per-scheme V024 one. That the two coincide for ZEROPAY is
corroboration, not coupling. I read it, changed nothing, and added a test that documents the relationship.

---

## 6. The error

`ErrorCode.SCHEME_CLOSED` (**409, `retryable=false`**) — a real canonical enum member in `lib-errors`, not
another stable-string workaround. That matters here specifically: smart-router throws `ApiException`, which
*requires* an `ErrorCode`, and payment-executor had been accumulating string codes because lib-errors was
frozen. Since `libs/**` was in scope, both now emit **one identical code with one identical status** rather
than two near-synonyms.

409 + non-retryable mirrors `PAYMENT_MODE_NOT_SUPPORTED`: a structured state of the corridor, not a fault,
and an immediate retry cannot succeed. It is "not retryable **at this time**" — the corridor reopens on its
published schedule, so the message names the window, the scheme-local time and the zone. Distinct from
`SCHEME_UNAVAILABLE` (transient technical, 503/retryable) and from the operator-driven `SCHEME_SUSPENDED`
(a manual hold, not a schedule). `SchemeClosedException` extends `PaymentException` so existing catch sites
keep treating it as a payment-layer refusal, but is deliberately neither a decline (the scheme was never
contacted) nor a timeout (there is no unknown outcome to reconcile).

---

## 7. Task 5 — the silent no-op, made loud rather than fail-closed

`FixtureOperationalStatusClient` now logs an **ERROR-level startup banner** naming the missing property and
each capability that is consequently absent ("the master pause, maintenance mode and all
partner/scheme/route suspensions are NO-OPS"). The new `FixtureSchemeOperatingHoursClient` and
smart-router's `UnverifiedSchemeOperatingHoursSource` do the same for their own capability.

**The ALLOW behaviour is unchanged, on purpose.** Making it fail-closed would stop every local sandbox and
every unit slice from taking a payment, and the real client already fails CLOSED when it *is* wired but
unreachable — which is the case that actually matters for the kill switch. The gap was never that the
fallback permits; it was that the fallback was invisible. `gmepay.scheme-hours.enforcement-enabled=false`
gets the same treatment: it works, and it announces itself.

Naming carries the same intent: smart-router's default source is `UnverifiedSchemeOperatingHoursSource` —
named for the verdict it *answers*, not for the data it lacks, exactly as `BusinessCalendar.empty()`
classifies every date UNVERIFIED rather than pretending to be a calendar.

---

## 8. Files

**Created** —
`libs/lib-api-contracts/.../SchemeAvailability.java`, `SchemeAvailabilityVerdict.java` ·
`config-registry/.../web/SchemeOperatingHoursController.java` ·
`payment-executor/.../domain/client/SchemeOperatingHoursClient.java`,
`domain/SchemeOperatingHoursGate.java`, `domain/SchemeClosedException.java`,
`client/rest/RestSchemeOperatingHoursClient.java`, `client/rest/FixtureSchemeOperatingHoursClient.java` ·
`smart-router/.../resolve/SchemeOperatingHoursSource.java`, `RestSchemeOperatingHoursSource.java`,
`UnverifiedSchemeOperatingHoursSource.java`.

**Modified** — `libs/lib-errors/.../ErrorCode.java` (+`SCHEME_CLOSED`) ·
payment-executor `domain/OperationalGate.java` (composition + 1-arg legacy ctor),
`domain/FailoverPaymentRouter.java` (per-candidate filter, `@Autowired` moved to the new ctor),
`domain/SchemeId.java` (+`canonicalCode`), `web/WalletPayController.java` (passes the dispatched scheme),
`web/PaymentExceptionHandler.java` (409 mapping), `application.properties` ·
smart-router `resolve/LocationSchemeResolver.java` (branch 5), `application.properties` ·
`Documentation/GAP_REGISTER.md`.

**Tests** — `SchemeAvailabilityTest` (lib-api-contracts, 12 cases) ·
`SchemeOperatingHoursGateTest`, `PaymentControllerSchemeClosedTest`, `FixtureClientLoudWarningTest`
(payment-executor) · `LocationSchemeResolverOperatingHoursTest` (smart-router) ·
`SchemeOperatingHoursControllerTest` (config-registry) · additions to `WalletPayControllerTest`.

---

## 9. Verification

```
gradlew :services:payment-executor:test  → BUILD SUCCESSFUL   426 tests, 0 failures
gradlew :services:smart-router:test      → BUILD SUCCESSFUL    59 tests, 0 failures
gradlew :services:config-registry:test   → BUILD SUCCESSFUL   501 tests, 0 failures
gradlew :libs:lib-api-contracts:test     → BUILD SUCCESSFUL
gradlew testClasses (repo-wide)          → BUILD SUCCESSFUL   (pre-existing deprecation warnings only)
```

Every timing test runs on a **fixed clock** and a named UTC instant with a known weekday, so no case can
pass "because it happens to be Tuesday".

The test that most directly answers "is the fix real?" is in config-registry: it loads the **shipped V024
seed** through Flyway into H2 and evaluates it with the same `SchemeAvailability.evaluate` the payment path
uses — walking ZEROPAY in 30-minute steps across a full week (336 evaluations, all OPEN), pinning that
18:00 KST is past-cutoff-but-open, and asserting each seeded scheme resolves in its **own** zone (one
instant where Seoul is already Tuesday while Bangkok is still Monday, i.e. two different weekday rows).
Fixtures alone could not have caught a seed the evaluator cannot read.

**Two real defects the tests caught while writing them:**

1. The 24x7 seed shape is `23:59:59`, so an exclusive close comparison reported **every rail as closed for
   the last second of every day**. The window test is inclusive at both ends, with the reason recorded next
   to it.
2. An overnight window (`close < open`, e.g. 22:00–06:00) evaluated as an *empty* window — i.e. a
   permanently closed scheme. Not seeded today, but a future corridor with real banking hours would have hit
   it; it is now handled as the union of the two day-parts and tested on both sides of midnight.

---

## 10. Open / follow-ups

1. **Nobody owns seeding the missing hours.** QRIS, KHQR, **NEPAL** and **SENDMN** have no V024 rows, so
   those corridors run permanently UNVERIFIED — enforcement is real but currently only bites ZEROPAY,
   BAKONG, NAPAS_247, PROMPT_PAY and FAST_SG. The alert makes this visible daily; closing it needs the
   schemes' published hours from an owner, then a migration.
2. **No holidays.** V024 is a *weekly* schedule with no date exceptions, so a rail closed for Seollal or
   Tết is not modelled. That is the `business_day_calendar` (V014) / `BusinessCalendar` axis, which is why
   consolidating the two eventually still matters — and why `BusinessCalendar` remains duplicated pending a
   promotion that can touch `scheme-adapter-zeropay` (§1).
3. **`gmepay.scheme-hours.enforcement-enabled=false` is a real override.** It banners at startup, but there
   is no alert if someone ships it that way.
4. **Cutoffs are reported, not yet acted on.** `pastCutoff` does not influence the settlement value date —
   that is settlement's decision and its behaviour was explicitly out of scope here.
5. **Nothing enforces the window inside the scheme adapters.** A caller that bypasses payment-executor and
   smart-router (a direct `/internal/scheme/...` call) is still ungated; the adapters were out of scope.
6. **transaction-mgmt still has no consumer**, as the audit noted. Nothing there decides routing, so
   nothing was added — but if a value-date field lands there, item 4 is its prerequisite.
7. **The read is cached for 10 minutes per scheme.** A migration that changes hours takes up to that long to
   take effect in a running executor. Acceptable for migration-seeded data; worth knowing during a cutover.
