> 작업: T3-11 outbound timeout coverage / 출처: agent

# T3-11 — closing the outbound-timeout residuals

Extends `outputs/agent/fix_t3-capacity-defects_2026-07-28.md` §9.6, which is the authority for how this
defect works. The one-sentence version: `HttpClientTimeoutAutoConfiguration` is a
`RestClientCustomizer`, Spring Boot applies customizers **only to the `RestClient.Builder` bean**, and
every call site using the static `RestClient.builder()` factory silently gets an uncustomised builder
with no read timeout — while the properties still resolve and still appear in `/actuator/env`. Invisible
from the config side and from the code side; only the two together reveal it.

Nothing was run against a live fleet. No Docker, no server, no load run. Everything below is proved by
tests, several of which drive real unresponsive TCP sockets.

---

## 0. Scoreboard

| # | Residual | Result |
|---|---|---|
| 1 | smart-router's three resolve-path clients | **CLOSED.** Injected builder + an explicit 500 ms/500 ms budget, proved against a real black-hole socket through the **production** constructors |
| 2 | Sweep the remaining baseline clients | **7 of 24 closed** (the 3 above + settlement-reconciliation 2 + reporting-compliance 2). 17 remain, all in files another agent held or outside the stated scope — enumerated in §6 |
| 3 | api-gateway's reactive `WebClient` | **CLOSED.** A new `WebClientTimeoutAutoConfiguration` (the reactive twin, sharing one set of numbers), 2 s/2 s for the edge, and the guard now sees `WebClient.builder()` too |
| 4 | reporting-compliance's 3 jobs on 1 thread | **CLOSED** (pool 4). Also fixed 3 more the guard was baselining: settlement-reconciliation 4→8, merchant-qr-data and qr-service unset→2 |
| 5 | ops-partner-bff's competing request-factory customizers | **Winner determined and pinned; the file itself deliberately untouched.** The finding is worse than "conflicting": the two are **tied**, so no ordering change can fix it. §5 |
| 6 | Tighten the guard so the baseline can only shrink | **CLOSED**, and the guard's detection is now itself tested against a deliberately untimed fixture in both stacks |

**Two findings worth more than the fixes**, both in §5 and §7: the "runs last" guarantee the first pass
relied on is not a guarantee, and the repository's most load-bearing ADR-016 test was racing its own
fixture in a way that could only ever fail *quietly wrong*.

---

## 1. smart-router (item 1) — the three on the live payment path

`resolveForPartner` / `resolveForCountry` / `weeklySchedule` all run **while a payment is being
routed**. All three built from the static factory, so a hung config-registry did not fail the resolve —
it held the routing thread, and the payment behind it, until the OS closed the socket.

All three now take the injected `RestClient.Builder` **and** declare an explicit per-hop budget:

```
gmepay.config-registry.connect-timeout-millis=500
gmepay.config-registry.read-timeout-millis=500
```

500 ms mirrors payment-executor's twin of the same hop (`gmepay.scheme-hours.*`), which reads the same
V024 table. These are in-cluster reference-data reads and each has a declared failure mapping already
written in its javadoc — and *that is the point of the fix*: **an unbounded hop does not degrade, because
a call that never returns never reaches its own catch block.** `RestSchemeOperatingHoursSource`'s class
javadoc promised "NEVER a failure ⇒ UNVERIFIED ⇒ candidate kept"; that promise was unreachable.

Both the injected builder *and* an explicit factory, not one or the other, deliberately: the explicit
factory is the reviewable budget, and the injected builder means deleting the budget line degrades to the
fleet floor rather than to nothing.

`gmepay.http.client.read-timeout` is also tightened to **5 s** for this service, so a *future* outbound
client added here is bounded inside payment-executor's 5 s budget for the resolve rather than outliving
the payment waiting on it. `OutboundBudgetNestingTest` reads payment-executor's **own** properties file
rather than restating the number, so tightening the caller without re-checking this service fails here.

### ADR-016: why fast failure is correct here, and where the line is

Resolution is **pre-submit**. Nothing has been sent to a scheme when these calls run, so there is no
irreversible operation whose outcome could be unknown and nothing a retry could double-send. That is why
these hops get the platform's tightest budget while a scheme *submit* gets a deliberately looser one —
the asymmetry is not about speed, it is about what a timeout *means*.

No retry was added. `ResolvePathTimeoutTest` counts the requests the unresponsive peer actually received
and asserts **exactly one** per resolve, because the country scan already fans out one request per
partner and an automatic retry would multiply pressure on an upstream that has just shown it cannot
answer.

### The control case

`theStaticBuilderHasNoReadTimeoutAtAll` runs the pre-fix expression verbatim on a thread that is then
abandoned, and asserts the thread is **still blocked** ten read-budgets later. Without it, every other
assertion in the file would pass equally well if the timeouts had never been needed.

---

## 2. The sweep (item 2)

Worked outward from the money path. Every fix is the same one line — take the builder as a parameter.

| Client | Path | Before | After |
|---|---|---|---|
| `smart-router/.../RestPartnerSchemeRegistry` | **live payment routing** | static factory, **no read timeout** | injected builder + 500 ms/500 ms |
| `smart-router/.../RestPartnerSchemeResolver` | **live payment routing** | static factory, **none** | injected builder + 500 ms/500 ms |
| `smart-router/.../RestSchemeOperatingHoursSource` | **live payment routing** | static factory, **none** | injected builder + 500 ms/500 ms |
| `settlement-reconciliation/.../RestPrefundingMovementClient` | nightly recon, leg (b) of the three-way tie-out | static factory, **none** | injected builder (2 s/10 s floor) |
| `settlement-reconciliation/.../RestRegistrationStatusClient` | gates settlement generation, **fail-CLOSED** | static factory, **none** | injected builder (2 s/10 s floor) |
| `reporting-compliance/.../RestCommittedFxTransactionPort` | BOK report scheduler | static factory, **none** | injected builder (2 s/10 s floor) |
| `reporting-compliance/.../RestTransactionClient` | KOFIU feed scheduler | static factory, **none** | injected builder (2 s/10 s floor) |
| `api-gateway/.../registry/RestConfigRegistryClient` | IP allowlist, inside a `GlobalFilter` | static `WebClient.builder()`, **none** | injected builder + reactive floor 2 s/2 s |
| `api-gateway/.../WebClientRbacClaimResolver` | every request when stamping is on | injected builder, but **no `WebClientCustomizer` existed** | reactive floor 2 s/2 s |
| `api-gateway/.../AuthIdentityCredentialStatusClient` | every partner request | injected builder + a per-call 3 s `.timeout(..)` operator only | reactive floor 2 s/2 s, **tighter than the operator** |

**Two of these deserve naming.** `RestRegistrationStatusClient` is documented as fail-CLOSED, so the
intended symptom of an outage was "settlement generation blocked, visibly". Unbounded, the symptom was
neither blocked nor visible: the call never returned and the generation window's scheduler thread was
held. And `RestPrefundingMovementClient` has a paging loop with a `MAX_PAGES` guard — which bounds the
number of *requests* while an unbounded read let a single one park the nightly recon indefinitely, so the
"partial pages are discarded" policy never ran because the page never completed.

**No probe was needed anywhere in this sweep, and that is a finding rather than a convenience:** every
client here is a read (`GET`, or a `POST` lookup at the edge). None is an irreversible submit, so there
is no ambiguous-outcome case, no `lookupStatus` probe to reach, and no retry to justify. Had one of them
been a write, the honest answer would have been to say so rather than add a retry — the two writes still
unbounded (`config-registry`'s credit-limit push, `ops-partner-bff`'s draft PATCHes) are in §6 for exactly
that reason.

`settlement-reconciliation`'s two share a `builderFor(..)` helper that a test binds `MockRestServiceServer`
to. The builder became a **parameter** rather than gaining a `.requestFactory(..)` call, deliberately: that
binding works by installing a request factory, so overwriting the factory afterwards silently detaches the
test from its mock and opens real sockets (the trap §1.5 of the first pass documented).

---

## 3. api-gateway's reactive stack (item 3)

`HttpClientTimeoutAutoConfiguration` bounds `RestClient`. **That type has nothing to do with
`WebClient`.** So the component every external request passes through sat outside the "fleet-wide" floor
altogether — and outside the guard written to catch precisely this, which scanned for
`RestClient.builder()` and could not see a `WebClient` at all.

That is the *same failure shape as the original defect, one layer up*: a mechanism whose reach was
assumed rather than measured. The guard certified its own blind spot.

**`libs/lib-errors/.../http/WebClientTimeoutAutoConfiguration.java`** is the reactive twin. It reuses
`HttpClientTimeoutProperties`, so there is **one** set of numbers for the fleet rather than two that
drift; `WebClientTimeoutTest#bothStacksShareOneSetOfNumbers` pins that.

Three details are load-bearing:

- **`responseTimeout`, not a read timeout.** Reactor Netty has no blocking-style read timeout; it has
  `HttpClient.responseTimeout(..)`, the bound between request sent and response status line arriving.
  That is exactly the failure being closed. A channel `ReadTimeoutHandler` would instead fire on any idle
  period including a legitimately slow-streaming body — a different, and here wrong, contract.
- **Connect goes on the channel option**, because that is where Netty reads it. Both halves are needed
  for the same reason as in the blocking case: no response timeout bounds a black-holed address, where
  the wait is the kernel's SYN-retry budget.
- **compileOnly + `@ConditionalOnClass`** on `WebClient`, reactor-netty's `HttpClient` and
  `ChannelOption`, so a service with no reactive stack is unaffected. Verified concretely:
  payment-executor's `testRuntimeClasspath` contains neither spring-webflux nor reactor-netty, so this
  auto-configuration provably cannot load there.

### 2 s, not the 10 s fleet floor

All three hops run on a Netty **event loop** inside the request path of an authenticated partner call,
against an in-cluster peer, and each has a declared degradation — pass through unstamped / answer 503 /
let the `fail-open` flag decide. None of those can run until the call gives up, so the budget *is* the
control. The 10 s floor is for reports and batch.

2 s is also **tighter than `AuthIdentityCredentialStatusClient`'s own 3 s reactive `.timeout(..)`**, so
the transport gives up first and **releases the socket** rather than the operator cancelling a
subscription over a connection that stays open. A per-call operator is a budget nested inside a transport
floor, never a substitute for one: it bounds the subscriber's wait, it is easy to omit — its sibling
`WebClientRbacClaimResolver` has none at all — and it is invisible in configuration.
`ReactiveOutboundTimeoutTest#shippedBudgetNestsInsideThePerCallOperator` reads the operator's constant
and the shipped YAML and fails if that ordering inverts.

### The guard now covers both stacks

`OutboundClientScanner` matches `WebClient.builder()` as well as `RestClient.builder()`, and accepts
`.clientConnector(` as the reactive escape hatch alongside `.requestFactory(`. Omitting the reactive
escape hatch is exactly how three real hops stayed invisible.

---

## 4. Scheduler pools (item 4)

| Service | Jobs | Was | Now | Why |
|---|---|---|---|---|
| **reporting-compliance** | 3 | **UNSET → 1** | **4** | The worst pairing in the fleet on the numbers. BOK `0 0 2 * * ?` and KOFIU `0 0 2 * * *` fire on the **same second, every day**; Hometax `0 0 2 L * ?` joins them on the last day of the month. They do not *risk* contending, they contend by construction. On one thread the second and third do not start until the first finishes, and each generates a regulatory file over a date-range query against transaction-mgmt — so "finishes" depends on a business day's row count and a remote service. A regulatory filing that silently did not run is the failure mode, and nothing about it looks like an error. |
| **settlement-reconciliation** | 7 | 4 | **8** | 4 was justified by an *argument* — "the 05:00/14:00/22:00 windows are hours apart and cannot overlap" — which is an unmeasured assumption about runtime duration, and the same argument that had already drifted into a real defect in payment-executor (7 jobs, pool 6). Weaker here: all three of those windows **generate and transmit a settlement file**, so their duration depends on a remote endpoint. |
| **merchant-qr-data** | 1 | UNSET → 1 | **2** | One job is not a starvation risk *for the job*. The victim is the **monitor**: `SchedulerLagProbe`'s heartbeat rides this pool, and a probe that cannot get a thread reports **no lag**. "The pool is fine" and "the pool is too busy to measure" must not be the same number. |
| **qr-service** | 1 | UNSET → 1 | **2** | Same, and it matters more than the job count suggests: the sweep is what expires CPM tokens, so a sweep that quietly stopped leaves presented-mode tokens usable past their expiry. |

`SchedulerPoolSizeWiringGuardTest` already scanned every service, so coverage needed no extension — but
its config lookup did: it read `application.properties` **or** `application.yml` and not
`application.yaml`, and several services ship two of the three. A service correctly sized in a file the
guard did not read would have been reported as UNSET, and a guard that cries wolf is a guard whose
failures get baselined. All three names are now read.

Baseline went from six services to **two** (`config-registry`, `ops-partner-bff` — both held by another
agent).

---

## 5. ops-partner-bff's competing customizers (item 5) — the finding, not the fix

The file was in another agent's scope, so `ClientBeans.java` is **untouched**. What was done instead is
determine the answer precisely and pin it from `libs/`, because the report's framing ("conflicting")
understates it.

**Which one wins today:** lib-errors'. **Why that is not good enough:** the two are **tied**.

`HttpClientTimeoutAutoConfiguration`'s customizer declares `Ordered.LOWEST_PRECEDENCE` so it runs last.
But the BFF's bean is a bare lambda declaring **no** order — and `AnnotationAwareOrderComparator` assigns
an unordered element `LOWEST_PRECEDENCE` too. So they compare **equal**, and the tie is broken by nothing
but bean-registration order: Spring's sort is stable, and auto-configuration bean definitions are
registered *after* user `@Configuration` ones, so the floor happens to come later and happens to win.

`LOWEST_PRECEDENCE` is the maximum value in the framework. **No ordering change can make this
deterministic while the competing bean exists** — which is why payment-executor's twin was deleted rather
than reordered, and why the BFF's must be too.

`CompetingRequestFactoryCustomizerTest` (lib-errors) does three things:

1. **Pins the current winner behaviourally.** A real Spring context with the competitor as a *user*
   configuration and the floor as an *auto*-configuration — the real relative registration order — then a
   real request to a real unresponsive socket. If the bare factory ever won, that call would not return.
   So "a timeout is genuinely applied" is answered by a socket, not by inspecting a bean.
2. **Proves the tie.** Sorts the pair in both input orders and shows each order survives its own sort,
   which is only possible when the comparator considers them equal. (The comparator's `getOrder(..)` is
   protected, so sorting is how the fact is obtained.)
3. **Guards the class of defect fleet-wide.** Fails when any service registers a `RestClientCustomizer`
   that calls `builder.requestFactory(..)` without declaring an order. Shrinking baseline, one entry:
   `ops-partner-bff/.../ClientBeans.java`, with the one-line fix stated on it.

**The fix for whoever owns that module:** delete the `patchCapableRequestFactoryCustomizer` bean. It is
redundant as well as hazardous — its whole purpose is PATCH support, and `HttpClientTimeouts.requestFactory`
installs a `JdkClientHttpRequestFactory` too, so deleting it keeps PATCH working *and* gains the BFF's
twelve upstream reads a read timeout they have never had.

---

## 6. What remains untimed, and why

**17 clients**, all one repeated pattern, all one line each:

| Where | Count | Why not done |
|---|---|---|
| `services/ops-partner-bff/.../client/rest/Rest*Client` | **12** | Concurrently owned by another agent. Twelve files, one pattern, one commit for whoever holds the module. Note these are also the clients that *include writes* (`patchDraftStep1..8`), so this is the one remaining group where the ADR-016 question is live rather than moot. |
| `services/config-registry/...` (`RestAuthIdentityClient`, `RestNotificationWebhookClient`, `KybInternalAuth`, `RestPrefundingCreditLimitClient`) | **4** | Concurrently owned by another agent. `RestPrefundingCreditLimitClient` **pushes a credit limit** — a write, and the one item in this table that should be looked at with ADR-016 in hand rather than treated as a mechanical fix. |
| `services/auth-identity/.../RestPartnerCredentialClient` | **1** | Outside the stated file scope. On the authenticated edge; should be next. |

The guard holds all 17 as a **shrinking baseline**: it fails when a new one appears *and* when a listed
one is fixed without being delisted, so the list can only get shorter. A second test forbids any
in-scope service from ever being listed — in-scope debt recorded as accepted debt is just debt that was
not paid. `IN_SCOPE_PREFIXES` was widened from 6 prefixes to 13 to cover everything this pass could touch.

**Also still open, unchanged from the previous pass:** Kafka repartitioning is an operational migration,
not a config change (existing topics keep their partition count; Azure Event Hubs Standard is fixed at
creation). `.smoke/infra-up.sh:24` still creates its topic with `--partitions 1`. The fleet is still 1
replica.

---

## 7. Item 6, and the guard-testing problem it exposed

The instruction was to make the guard fail on a *new* untimed client rather than record today's count.
The guard already derived its list from source and already failed on new offenders — but **nothing
anywhere proved it would notice one.** Run against the real tree it can only ever report "no unexpected
offenders", which is also what it would report if its pattern were misspelled, if the walk skipped
`src/main`, or if comment-stripping ate the match. There is no observation of the real tree that
distinguishes "the fleet is clean" from "the detector is broken".

That is the T3-11 mistake again, in the test written to prevent it.

So the detection rule moved into `OutboundClientScanner`, which takes a scan root, and
`OutboundHttpTimeoutGuardDetectsNewOffendersTest` points it at a `@TempDir` fixture:

| Fixture | Asserted |
|---|---|
| A new client using static `RestClient.builder()` | **detected** |
| The same client taking the injected `RestClient.Builder` | not detected |
| Static factory + its own `.requestFactory(..)` | not detected |
| A new `WebClient` using static `WebClient.builder()` | **detected** — this fixture scanned *clean* before the reactive pattern was added |
| The same taking the injected `WebClient.Builder` | not detected |
| Static factory + its own `.clientConnector(..)` | not detected |
| A static factory named only inside a `//` or `/* */` comment | not detected |
| A `src/test/` fixture and a `build/` generated file | not detected |

Each positive case carries its negative twin, because a detector that flags everything passes the
positive case and is useless.

**Both guards were then mutation-checked end to end**: reverting one smart-router client to the static
factory and setting reporting-compliance's pool to 3 made
`OutboundHttpTimeoutWiringGuardTest#everyOutboundClientIsReachableByATimeout` and
`SchedulerPoolSizeWiringGuardTest#everyScheduledServiceSizesItsPoolAboveItsJobCount` **both fail**; the
mutation was then reverted and both pass. A guard nobody has watched fail is a guard nobody should trust.

---

## 8. The repository's most important ADR-016 test was racing its own fixture

`TimedOutSubmitResolvedByProbeTest` — the test the previous pass called the most important one, which
wires the real `RestSchemeClient` against a real `HttpServer` into the real `FailoverPaymentRouter` —
failed twice during this work's verification, in the **full** payment-executor suite and never in
isolation. It is worth writing up because of *how* it failed.

**The race.** The client abandons the submit at its read timeout and the router carries on immediately,
so a request can be delivered to the server **after `pay()` has already returned**. The test read
`submits.get()` / `statusProbes.get()` at that instant. Under load the network loses the race, and the
observed failures were `submits == 0` and `statusProbes == 1` for calls that had provably been sent.

**Why that is the dangerous kind of failure.** "The scheme never received the submit" and "the test
looked too early" are the *same number*. A probe that never arrived is not the scheme saying `NOT_FOUND`
— so the control case, whose entire job is to prove the other two are not vacuous, was capable of
reporting a fact it had not established. Note also that a late arrival is not a flaw in the behaviour
under test; **it is the behaviour under test.** A submit the client stopped waiting for, which the scheme
then receives and may act on, is exactly the ambiguous outcome ADR-016 §4 exists for.

**The fix, which strengthens rather than loosens.** `awaitRequestsThenQuiesce(expectedSubmits,
expectedProbes)` waits up to 5 s for the expected arrivals, then waits a further 500 ms and asserts the
counts are **exactly** N. The quiescence window is what keeps "sent exactly once" honest: waiting for
"at least one" alone would accept a duplicate that landed a moment later — and a duplicated irreversible
submit is the worst outcome in that file. The previous version could not have caught a late duplicate at
all.

Two coupled fixture defects were fixed alongside it: the scheme's executor was a **fixed pool of 4**
while each hung submit handler pinned a thread for `20 ×` the read timeout, so the number of probes the
fixture could serve depended on how many submits had already hung — the quantity under test. It is now
an unbounded cached pool with an `8 ×` hold, and `@AfterEach` calls `shutdownNow()` because
`HttpServer.stop(0)` does not interrupt handler threads, so every test method was leaking sleeping
threads into the rest of the suite.

Verified with **two consecutive full-suite `:services:payment-executor:test` runs, both green**, plus the
class in isolation.

---

## 9. Verification performed

| Check | Result |
|---|---|
| `gradlew testClasses` (whole repo) | **BUILD SUCCESSFUL** |
| `:libs:lib-errors:test` | green |
| `:services:smart-router:test` | green (8 new tests) |
| `:services:api-gateway:test` | green (4 new tests) |
| `:services:reporting-compliance:test`, `:services:settlement-reconciliation:test`, `:services:merchant-qr-data:test`, `:services:qr-service:test` | green |
| `:services:payment-executor:test` | green **twice**, full suite (§8) |
| `:services:transaction-mgmt:test`, `:services:notification-webhook:test`, `:services:prefunding:test`, `:services:rate-fx:test`, `:services:revenue-ledger:test` | green — the new reactive auto-configuration did not disturb them |
| `:services:scheme-adapter-zeropay:test`, `:services:scheme-adapter-nepal:test`, `:libs:lib-events-kafka:test` | green |
| Mutation check: both guards fail on a deliberate regression, pass after revert | **confirmed** (§7) |
| `check_internal_auth_wiring.py` | 121/121 |
| `check_monitoring_wiring.py` | 37/37 |
| `check_helm_chart_wiring.py` | 299/299 |
| `check_gitleaks_config.py` | findings=0, missed=0, false-positives=0, re2-bad=0 |
| PyYAML parse: `docker-compose.yml` + all four Helm values + the three touched service YAMLs | all parse; reporting-compliance pool=4, settlement pool=8, api-gateway `http.client` = 2 s/2 s |

No manifest was changed (`docker-compose.yml` and `deploy/helm/**` are untouched by this pass); the
scripts were re-run anyway because service configuration moved.

### New tests

| File | What it proves |
|---|---|
| `libs/lib-errors/.../http/WebClientTimeoutAutoConfiguration.java` (+ `WebClientTimeoutTest`) | A real socket that accepts and never answers terminates the `Mono`; the connector is installed; the customizer runs last; one request, never two; both stacks share one property set; the shared `enabled` switch governs both |
| `libs/lib-errors/.../http/OutboundClientScanner.java` | The detection rule, extracted so it can be aimed at a fixture instead of only at the real tree |
| `libs/lib-errors/.../http/OutboundHttpTimeoutGuardDetectsNewOffendersTest.java` | 8 fixture cases: the guard **detects** a new untimed client in both stacks, does not flag the fixed forms, does not flag comments, `src/test/` or `build/` |
| `libs/lib-errors/.../http/CompetingRequestFactoryCustomizerTest.java` | The floor wins the race against an unordered competitor — proved by a socket; the pair is **tied**, so ordering cannot fix it; no new competitor may appear |
| `services/smart-router/.../ResolvePathTimeoutTest.java` | All three resolve-path clients bounded through their **production** constructors against a real black hole; `SCHEME_UNAVAILABLE` / degraded-to-UNVERIFIED reached; exactly one request per call; and a control case showing the pre-fix expression is still blocked ten budgets later |
| `services/smart-router/.../OutboundBudgetNestingTest.java` | The resolve budget nests inside this service's floor, which nests inside payment-executor's — read from payment-executor's own file, not restated |
| `services/api-gateway/.../ReactiveOutboundTimeoutTest.java` | The IP-allowlist read and the credential-status read terminate against a real black hole; the transport fires before the per-call operator; the customizer is present, not merely functional; the shipped budget nests inside the operator |
| `services/payment-executor/.../TimedOutSubmitResolvedByProbeTest.java` (rewritten assertions) | Same three ADR-016 cases, now observed rather than sampled, and now able to catch a **late** duplicate submit that the previous version could not (§8) |
