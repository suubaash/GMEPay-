> 작업: revenue-ledger ShedLock + POISON requeue / 출처: agent

# Two follow-ups closed: the last unlocked scheduler, and the terminal replay state

**Branch:** `feat/exec-gap-closure-2026-07-28` · **Scope touched:** `services/revenue-ledger`,
`services/payment-executor`, `e2e-tests`, plus `Documentation/GAP_REGISTER.md`, two CHANGELOGs and this
report. **`libs/**` was not touched** — the ShedLock wiring needed nothing there, because the pattern the
other five services use is per-service by design (each has its own datasource and its own `shedlock`
table; a shared lib would have to reach across databases).

No Docker, no server, no fleet was started.

---

## 0. Scoreboard

| # | Item | Verdict |
|---|---|---|
| 1 | revenue-ledger outbox publisher has no ShedLock | **CLOSED** — defect 3 of T3-11 is now closed fleet-wide |
| 2a | No requeue path out of POISON | **CLOSED** — `POST /internal/ops/revenue-posting-failures/requeue` |
| 2b | 406 classified as a permanent rejection | **CHANGED to retryable**, with 404/405/415, on a stated boundary |
| 3 | `WalletScanPayE2ETest` asserts only the fee journal | **CLOSED, and the premise was wrong** — see §4 |

**The most important finding is in §4**, and it corrects the earlier report: `WalletScanPayE2ETest`
could not have caught the 406 no matter how its journal assertions were written.

---

## 1. Item 1 — revenue-ledger's outbox publisher is locked

### What was actually at risk

`OutboxPublisher#publishPending` (`@Scheduled(fixedDelayString = "${gmepay.outbox.poll-ms:1000}")`) was
the last unlocked `@Scheduled` method in the fleet. Its failure mode at N>1 is not a duplicate log line:

```
select unpublished rows  →  publish(event)  →  stamp published_at
                            ^^^^^^^^^^^^^^^
                            the whole read-to-stamp window is unguarded
```

Two replicas ticking a second apart both select the same batch and both publish it. The `@Transactional`
on the method makes the tick atomic *within* one JVM and does nothing about the second one.

Consumers are contractually idempotent — the class documents at-least-once — and that is the reason this
is a correctness bug rather than an outage: **"at least once" is a bound on redelivery of the same event,
not a licence to multiply publishes by the replica count.** These are the events reporting and
reconciliation aggregate.

### The fix, deliberately identical to the other four

| Piece | Value |
|---|---|
| Dependencies | `shedlock-spring:5.16.0` + `shedlock-provider-jdbc-template:5.16.0` — the same version as payment-executor / settlement-reconciliation / notification-webhook / scheme-adapter-zeropay / prefunding / transaction-mgmt |
| `ShedLockConfig` | `@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")`, `JdbcTemplateLockProvider` with `usingDbTime()`, `@ConditionalOnMissingBean` |
| Migration | **V007** — next free version in this module (V001–V006 existed) |
| Vendor dirs | **None to mirror.** Only `config-registry` has `db/vendor/{h2,postgresql}`; revenue-ledger has a single `db/migration` tree. Verified, not assumed |
| Annotation | `@SchedulerLock(name = "RevenueLedgerOutboxPublisher_publishPending", lockAtMostFor = "PT5M", lockAtLeastFor = "PT0S")` |

Three subtly different lock implementations across one fleet is how one of them ends up wrong, so this is
a copy on purpose rather than an improvement.

**`usingDbTime()`** — lock expiry follows the *database* clock. Two pods whose wall clocks differ by
seconds would otherwise disagree about whether a lock had lapsed, and the fast one would start a second
drain while the first was still publishing.

**`lockAtMostFor = PT5M` is a crash safety net, not a runtime budget.** A batch of 100 publishes takes
milliseconds, so five minutes only ever elapses if the holder died. Sizing it *short* is the dangerous
direction: an early expiry admits the concurrent drain the lock exists to prevent.

### Also set: `spring.task.scheduling.pool.size=2`

T3-11 defect 2's own follow-up for this service, one line, included because it is the same failure class.
Spring's silent default is **one** thread for every `@Scheduled` method in the context, and lib-errors'
`SchedulerLagProbe` registers a second fixed-rate task on the same registrar. At pool size 1 a slow
outbox tick starves the probe — so the one signal that would reveal a starved scheduler goes quiet
exactly when it matters. 2 = job count + heartbeat.

### The tests

`services/revenue-ledger/src/test/java/com/gme/pay/ledger/config/ShedLockTest.java`, the same two
enforcement halves the other four services got plus two more:

1. **Second holder refused** — a real H2 datasource with the **full Flyway migration set** applied, so
   V007 is proved to apply on top of V001–V006 and to produce the table ShedLock actually expects.
   Acquire → second acquire refused → unlock → acquire succeeds.
2. **The lock row lands in the migrated table**, keyed by job name — proves the provider is talking to
   V007's table rather than succeeding in memory.
3. **Reflection guard** over every `@Scheduled` method: fails when one lacks a uniquely-named
   `@SchedulerLock`, when a lock name is reused, or when the job count changes. This is precisely the
   mechanism whose absence let revenue-ledger become the last unlocked service.
4. **The shipped pool size** is read out of `application.properties` and asserted to cover job count +
   heartbeat.

---

## 2. Item 2a — the requeue path

### The shape of the hole

`POISON` is terminal by design, and the design is sound: *"a POISON row is never retried again — that is
the point, it must not hammer a permanently-broken downstream."* That reasoning holds **only while the
reason is a property of the row.**

T3-12 made it a property of the *server*. Revenue-ledger returned 406 on both journal endpoints because
of a content-negotiation defect; the replay client called 406 a permanent rejection; the sweep poisoned
the row on the **first** attempt. A bug fixed the same week had permanently orphaned every
`ROUNDING_RESIDUAL` and `REVERSAL_JOURNAL` posting, recoverable only by hand-editing
`revenue_posting_failures` in production.

### `POST /internal/ops/revenue-posting-failures/requeue`

```json
{ "postingTypes": ["ROUNDING_RESIDUAL","REVERSAL_JOURNAL"],
  "ids": [41, 42],
  "reason": "revenue-ledger 406 content-negotiation defect fixed (T3-12)" }
```

Moves POISON rows to `PENDING`, `attempts=0`, `next_attempt_at=now()`. Header `X-Operator-Id` attributes
it. Answers 200 with `{matched, requeued, skippedNotPoison, skippedUnreplayable, requeuedReferences}`.

**It does not replay.** Requeue makes rows due; the sweep sends them. Keeping the two separate lets an
operator requeue, inspect what became PENDING, and only then trigger `POST /replay` — rather than
discovering the shape of a mass re-send after it has happened.

Each property and why:

| Property | Reasoning |
|---|---|
| `attempts` resets to **0** | Leaving it at the exhausted value would poison the row again on the very next sweep — a no-op dressed as a fix. |
| **Idempotent** | Only POISON is selected. A second identical call finds the rows already PENDING and requeues 0, still 200. A nervous operator running the command twice must be a no-op, not a corruption. |
| **Audited** to `ledger_ops_runs` | `job=REVENUE_POSTING_REQUEUE`, `trigger=OPERATOR`, operator id, `summary="matched=.. requeued=.. reason=.."`. Load-bearing rather than decorative: resetting `attempts` **discards the row's own history** of how many times the posting was pushed at the ledger, so this row is the only place it and the *why* survive. Needed Flyway **V012** to widen `ck_ledger_ops_runs_job`, which V008 had pinned. |
| **Internal-auth gated** | Inherited from the wholesale `/internal/**` rule in `SandboxSurfaceInternalAuthConfig`, and asserted over real HTTP rather than assumed — the gate is a servlet filter a slice test never runs, and the endpoint re-arms money postings for automated re-send. |
| **Unfiltered requeue is 400** | "Requeue every POISON row" is a much larger decision than "requeue what the 406 broke". An empty body must not silently mean the larger one. |
| **Unknown posting type is 400** | Silently dropping a typo would report `requeued=0` and let the operator believe the backlog was already clear. |
| **Unreplayable rows skipped** | A row poisoned because no payload was ever captured cannot be re-POSTed at any future point; requeueing it would spend the whole budget arriving back at POISON. Counted and reported separately so the operator sees they were considered. |
| **`ABANDONED` untouched by a type filter** | Abandoning is a human's judgement that the posting should not be booked. A bulk requeue overturning it silently would make the status meaningless. Naming the id is an explicit act — and even then the row is reported as `skippedNotPoison` rather than moved, because only POISON is requeueable whatever the selector. |
| **Bounded** | `gmepay.revenue-posting-replay.requeue-max-rows` (default 1000) so one request cannot re-arm an unbounded backlog against a revenue-ledger that may still be fragile. |
| **Rejection is a 400, not a FAILED run** | A selector mistake is thrown *before* the run wrapper, so a typo does not write a FAILED `ledger_ops_runs` row and page someone. |

**Operator procedure now** (replacing the manual SQL in the earlier report):
`GET /rows?status=POISON` → `POST /requeue` with the types → `POST /replay`.

---

## 3. Item 2b — the 406 decision, and why

**Decision: 406 becomes retryable. So do 404, 405 and 415. Everything else 4xx still terminates.**

### Why the old classification was wrong on its own terms, not merely unlucky

A 406 is content negotiation: *the server cannot produce a representation acceptable to the client.* It
says nothing whatsoever about whether the posting is valid. Between two services we deploy ourselves,
over a contract we own, it can only mean a **version mismatch** — which is exactly the condition a retry
after a deploy fixes, and exactly the condition burying the row makes worse.

The old comment — *"a 4xx on a replay means revenue-ledger has judged this exact body invalid"* — is true
of 400/409/422 and simply false of 406. The class was over-broad.

### Where the line is drawn, and why it is a real line

The four codes now treated as deployment mismatches are precisely the ones **Spring MVC raises from
routing and content negotiation, before the handler method ever reads the body**:

| Code | Raised by | Means |
|---|---|---|
| 404 | `NoHandlerFoundException` | the path is not mapped on the deployed server |
| 405 | `HttpRequestMethodNotSupportedException` | the path is mapped, the verb is not |
| 415 | `HttpMediaTypeNotSupportedException` | request-side negotiation failed |
| 406 | `HttpMediaTypeNotAcceptableException` | response-side negotiation failed |

A 400 is the opposite: `HttpMessageNotReadableException` or bean validation, both of which have *read the
body and judged it*.

**401/403 are deliberately excluded** despite also being pre-handler. They are an
authentication/authorisation verdict, and re-presenting rejected credentials on a schedule is a bad
pattern regardless of whether the row survives it. Naming the exclusion is the point — the boundary is
"routing and negotiation", not "anything the framework raised".

Verified against the actual callee before widening: revenue-ledger's web layer contains **no** `404`
response anywhere, so no business answer is expressed as one of these codes.

### This is not "retry everything"

- The eight-attempt exponential budget is unchanged. A genuinely permanent 406 still reaches POISON —
  after a deploy window (roughly 2 h with the shipped 60 s base) instead of within one sweep.
- 400/409/422 still poison on the first sweep, asserted by test. For a body the server read and refused,
  the fast alert is the right answer; retrying only delays it by the whole backoff schedule.

### Why both 2a and 2b were needed

Retryability means *this* case does not arise again. It does not mean the next one won't: **no status
classification is going to be right about every future server defect.** Retryability buys hours; a defect
discovered a week later needs the requeue path. They are complementary, and shipping only one would have
left the actual hole open.

---

## 4. Item 3 — the E2E assertions, and a correction to the earlier report

### The premise was wrong, and this matters more than the fix

The earlier report said `WalletScanPayE2ETest` "asserts only the fee journal" and that this "is the
assertion gap that let 200 real payments run with no residual journal". Both halves are wrong, and the
reason is the interesting part.

1. **The "fee journal" *is* the rounding-residual journal.** `GmeremitPaymentService` books the ₩500
   service fee by calling `postRoundingResidual` (`GmeremitPaymentService.java:246`). The test was
   already asserting a `ROUNDING_RESIDUAL` posting. Calling it "the fee journal" is why nobody connected
   the assertion to the gap.

2. **The residual journal was never missing.** The 406 is raised during converter *selection*, which
   happens **after** the handler returns and therefore after `journalStore.save(..)` has committed. The
   journal landed on every call. What failed was only the response.

So the ledger-state assertion was **structurally incapable** of catching this defect — it was asserting
the one thing that was still working. The only symptom was on the client side: `RestRevenueLedgerClient`
saw a 406 and wrote a `revenue_posting_failures` row. That is the 12th row per transaction where a
healthy deployment writes 11.

### What the test now asserts

| # | Assertion | Why |
|---|---|---|
| 1 | The residual journal by **account code** — `DEBIT RECEIVABLE_PARTNER / CREDIT REVENUE_ROUNDING`, balanced, ₩500 KRW | The old check accepted *any* balanced journal containing a ₩500 line, which a revenue-capture posting of the same amount would satisfy. Renamed `assertRoundingResidualJournalPosted` so the posting type is visible. |
| 2 | **payment-executor recorded no `revenue_posting_failures` row for the payment** (`GET /internal/ops/revenue-posting-failures/rows`, polled over a settle window) | **This is the assertion that would actually have caught T3-12.** It asserts the caller also believes it succeeded, which is the only place the defect was visible. |
| 3 | `POST /v1/journals/reversal` against the **live fleet**: 200, a non-empty body carrying `journalId`, and a read-back as `DEBIT REVENUE_REVERSAL / CREDIT RECEIVABLE_PARTNER` | The second endpoint that carried the identical defect, and the one nobody noticed. |

**Stated honestly about #3:** it proves the *endpoint*, not a cancelled payment end to end. This fleet has
no cancel journey to drive — the wallet path authorizes and captures in one call, and reversals come from
`PaymentOrchestrator`'s separate `/v1/payments` lifecycle, which would need prefunding and rate services
added to the fleet. A cancel journey was **not** invented to make an assertion look complete. It is
recorded here and in the test's javadoc as what remains.

An assertion that the body is non-empty is included deliberately: a `{}` with a 200 on it is the same
defect wearing a success code, which is exactly what disabling `FAIL_ON_EMPTY_BEANS` would have produced.

**Not run**: `e2eTest` launches nine Spring Boot processes, and the task forbade starting a server. The
E2E changes are compile-verified only (`:e2e-tests:compileTestJava` green). Every other test below was
actually executed.

---

## 5. Verification

| Check | Result |
|---|---|
| `:services:revenue-ledger:test` | **132 tests, 0 failures, 0 errors, 0 skipped** |
| `:services:payment-executor:test` | **500 tests, 0 failures, 0 errors, 0 skipped** |
| `:e2e-tests:compileTestJava` | green (not executed — would start a fleet) |
| `gradlew testClasses` (whole repo) | **BUILD SUCCESSFUL** |
| `check_internal_auth_wiring.py` | 94/94 |
| `check_monitoring_wiring.py` | 37/37 |
| `check_helm_chart_wiring.py` | 193/193 |
| `check_gitleaks_config.py` | findings=0, missed=0, false-positives=0, re2-bad=0 |
| `check_load_harness_wiring.py` | OK |
| `node docker/keycloak/check-topology.mjs` | 101/101 |
| PyYAML parse: `docker-compose.yml` + all four Helm values | all four + compose parse as dicts |

### New / changed tests

| File | What it proves |
|---|---|
| `services/revenue-ledger/.../config/ShedLockTest.java` **(new)** | Second holder refused on a real H2 + full Flyway set (V007 applies on V001–V006); the lock row lands in the migrated table; the reflection guard fails on an unlocked or duplicate-named `@Scheduled`; the shipped pool size covers job count + heartbeat. |
| `services/payment-executor/.../replay/RevenuePostingRequeueServiceTest.java` **(new)** | POISON→PENDING with `attempts=0` and due-now; the untargeted type is untouched; idempotent (second call requeues 0 and does not overwrite the first reason); the audit row is written with the right job/trigger/operator/count/summary — which also proves V012's widened CHECK; no-selector and unknown-type are refused *without* writing a FAILED run; id targeting and overlap de-duplication; unreplayable rows skipped; `ABANDONED` never moved; a requeued row is visible to `findDueForReplay`. |
| `services/payment-executor/.../web/RevenuePostingRequeueEndpointTest.java` **(new)** | Real HTTP on a random port: 401 with no token and with a wrong token (and the row stays POISON); 200 + `requeued:1` for the T3-12 type pair; idempotent over the wire; `{}` → 400 `REQUEUE_NO_SELECTOR`; mistyped type → 400 `REQUEUE_UNKNOWN_POSTING_TYPE`; id selector. |
| `services/payment-executor/.../replay/RestRevenuePostingReplayClientTest.java` | Three new cases: 406 is TRANSIENT (the T3-12 regression pin, with the reasoning in the assertion message); 404/405/415 likewise; 400/409/422/401/403 still PERMANENT. |
| `e2e-tests/.../WalletScanPayE2ETest.java` | Residual asserted by account code; no `revenue_posting_failures` row for the payment; the reversal endpoint books and reads back. |

### Flyway

| Module | Version | Shape |
|---|---|---|
| revenue-ledger | **V007** | additive `CREATE TABLE IF NOT EXISTS shedlock`, canonical schema, engine-neutral. V001–V006 existed. No `db/vendor/**` in this module. |
| payment-executor | **V012** | widens `ck_ledger_ops_runs_job` by one value. V011 (the T3-11 shedlock table) was the previous head. Additive — the constraint is only ever loosened, so it cannot fail on existing rows. |

---

## 6. Replica ceiling: still 1

Item 1 removed **scheduler duplication as a reason for any service** — every `@Scheduled` job in the
fleet is now distributed-locked. It did **not** move the fleet-wide ceiling.

| Service | Safe at N>1? |
|---|---|
| payment-executor, settlement-reconciliation, notification-webhook, scheme-adapter-zeropay, transaction-mgmt, prefunding | Yes, for scheduler correctness |
| **revenue-ledger** | **Yes now** (this work) — was the last holdout |
| **api-gateway** | **No** — T0-7: `InMemoryRateLimitStore` (effective cap becomes N × configured) and `InMemoryNonceStore` (a captured signed request replayable once per replica — an integrity defect, not a throughput one) |
| **transaction-mgmt idempotency** | **No** unless Redis is configured — the in-memory store gives a per-replica 24 h window, so one retry creates N transactions |
| **ops-partner-bff** | **No** — per-JVM paging de-dup would double-page |

**So the fleet-wide answer is still 1 replica.** Every remaining blocker is a *shared-state build* (Redis
or equivalent), not a config flag — which is a different and larger piece of work than a lock table.
Kafka consumers additionally gain nothing from added replicas until the topics are repartitioned (T3-11
§4), and three consumer factories (revenue-ledger, prefunding, ops-partner-bff) still never call
`setConcurrency`.

---

## 7. Still open, recorded rather than half-done

1. **Three bare Kafka consumer factories** — `RevenueLedgerKafkaConsumerConfig`,
   `PrefundingKafkaConsumerConfig`, `OpsAlertKafkaConsumerConfig` still never call `setConcurrency`, so
   `spring.kafka.listener.concurrency` is unreadable there. One line each. Not done because it is T3-11
   follow-up 3, not either of these two items.
2. **An E2E cancel/refund journey.** §4 #3 proves the reversal endpoint, not a cancelled payment end to
   end. Closing it properly means adding the `/v1/payments` authorize→capture→cancel lifecycle (and
   prefunding + rate services) to a fleet whose test is named for the wallet-QR journey — arguably its
   own test class.
3. **`ledger_ops_runs` still has no missed-run detection and no retention pruner** (T2-5 caveat (e),
   unchanged). The requeue adds rows to that table without adding a way to notice one that was never
   written.
4. **Nothing here was exercised against a running fleet.** The lock is proved by a real H2 + Flyway
   database and the requeue by real HTTP on a random port, but no second replica has ever actually
   existed.
