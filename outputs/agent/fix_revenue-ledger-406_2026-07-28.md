> 작업: revenue-ledger 406 rounding-residual / 출처: agent

# `POST /v1/journals/rounding-residual` returned 406 on every payment

**Branch:** `feat/exec-gap-closure-2026-07-28` · **Scope touched:** `services/revenue-ledger` only
(plus `Documentation/GAP_REGISTER.md` and this report). `services/payment-executor` was **read, never
edited** — and did not need editing.

**Register:** now tracked in its own right as **T3-12** under *Newly discovered while fixing*. It was
previously only a sentence inside T3-5's note.

---

## 1. Root cause

**It is response-side content negotiation on the server. The client is not at fault.**

`RoundingResidualController` returned the **domain** object:

```java
Journal journal = ledgerPostingService.postRoundingResidual(...);
return ResponseEntity.ok(journal);          // <-- the defect
```

`Journal` (and `LedgerEntry`) are plain `final class`es with record-**style** accessors and **no**
JavaBean getters:

```java
public String journalId() { return journalId; }      // not getJournalId()
public Instant postedAt()  { return postedAt; }
public List<LedgerEntry> entries() { return entries; }
```

They are *not* Java records, so Jackson's bean introspection does not treat those as properties and
discovers **zero** properties on the type. The chain from there:

1. With the default `SerializationFeature.FAIL_ON_EMPTY_BEANS`, `ObjectMapper.canSerialize(Journal.class)`
   returns **false** (no serializer can be built for a bean with no properties).
2. `MappingJackson2HttpMessageConverter.canWrite(Journal.class, mediaType)` consults exactly that call,
   so it returns **false**.
3. `AbstractMessageConverterMethodProcessor` finds **no** converter able to produce any representation
   of the return value and throws `HttpMediaTypeNotAcceptableException`.
4. Spring maps that to **HTTP 406 Not Acceptable**.

**Why 406 and not 500** — which is what threw everyone off: the failure happens during converter
*selection*, before serialization is attempted. A serialization failure would be a 500; an
*unserializable return type* is a 406.

Ruled out explicitly, each by inspection:

| Candidate | Verdict |
|---|---|
| endpoint `produces`/`consumes` declaration | Not present anywhere in the service — not the cause |
| missing/wrong `Accept` on the client | Client sends **no** `Accept` (= `*/*`), which is correct and cannot cause a 406 by itself |
| missing/wrong `Content-Type` on the client | Client sets `application/json` correctly (a mismatch here would be **415**, not 406) |
| message converter not registered | Jackson converter is registered normally; it simply declines this *type* |
| **return type with no converter** | **This one.** Confirmed by reproduction below |
| global content-negotiation config | None exists in `revenue-ledger` or `libs/**`; nothing was loosened |

### Evidence (reproduction, before any fix)

The new test run against the **unmodified** controller:

```
RevenueLedgerHttpContractTest > roundingResidual_returns200AndSerialisableJournalBody() FAILED
    java.lang.AssertionError: Status expected:<200> but was:<406>
RevenueLedgerHttpContractTest > roundingResidual_withoutAcceptHeader_returns200()      FAILED
    java.lang.AssertionError: Status expected:<200> but was:<406>
RevenueLedgerHttpContractTest > reversalJournal_returns200AndSerialisableJournalBody() FAILED
    java.lang.AssertionError: Status expected:<200> but was:<406>
8 tests completed, 3 failed
```

with `HttpMediaTypeNotAcceptableException: No acceptable representation` in the resolved-exception
slot. This matches the measured run in `fix_t3-capacity-sla_2026-07-28.md` §2 exactly (1.00
`revenue_posting_failures` rows per payment; 12 rows/txn where a healthy deployment writes 11).

---

## 2. The layer fixed

**The endpoint** — because the endpoint is what is wrong. No client change is required anywhere, so
nothing in `payment-executor` was touched and no "tolerance" was added to the server to paper over a
client.

New `services/revenue-ledger/.../web/JournalResponse.java` — a Java **record** (natively introspectable
by Jackson) mirroring the journal's documented wire shape, with `@JsonFormat(shape = STRING)` on the
money field per `MONEY_CONVENTION.md`. `RoundingResidualController` now returns
`JournalResponse.from(journal)` from both POSTs.

**What was deliberately not done:**

- **Not** `spring.jackson.serialization.fail-on-empty-beans=false`. That would have turned every 406
  into a **200 with an empty `{}` body** — the same defect with a success code on it, and it would have
  applied service-wide.
- **Not** a `produces = APPLICATION_JSON_VALUE` on the mapping. That changes which media types are
  *offered*, not whether a converter can write the type; the 406 would have survived.
- **Not** `@JsonProperty`/`@JsonAutoDetect` annotations on the domain `Journal`/`LedgerEntry`. That
  would put wire concerns into the domain model and make the ledger's core types serialization-coupled.

The JSON shape is **unchanged** from what the endpoint contract always documented, and no posting,
account code, amount or status code changed. The 204 (zero residual) and 400 (validation) branches are
untouched and pinned by tests.

---

## 3. What test level now covers it

**A `@WebMvcTest` slice — real HTTP through the real, Boot-configured converter chain and real content
negotiation.** `services/revenue-ledger/src/test/java/com/gme/pay/ledger/web/RevenueLedgerHttpContractTest.java`,
8 tests.

This is deliberately a step above the existing controller tests. Those use
`standaloneSetup(...).setMessageConverters(new MappingJackson2HttpMessageConverter(om))`, which is fine
but hand-builds the converter list; the slice takes the converters Boot actually configures at runtime.

More to the point: **the two `/v1/journals` POST endpoints had no HTTP-level test of any kind.** They
were covered only at service level (`RoundingResidualTest`, `RevenueReversalRoundingResidualTest`,
`JournalPersistenceIT`), and a service-level test never touches a message converter — which is precisely
why a fully green suite coexisted with an endpoint that 406'd on 100% of production calls.

The tests assert **response bodies**, not just statuses, so a regression that returns an empty body
cannot pass. They include the **exact request shape the real client sends** (no `Accept` header), the
204 zero-residual branch, and the 400 validation branch.

---

## 4. Sibling endpoints checked

All four endpoints `RestRevenueLedgerClient` posts to were exercised over real HTTP with the same client
configuration:

| Endpoint | Response type | Before | Now |
|---|---|---|---|
| `POST /v1/journals/rounding-residual` | was domain `Journal` | **406** | 200 + JSON body |
| `POST /v1/journals/reversal` | was domain `Journal` | **406** — same defect | 200 + JSON body |
| `POST /v1/revenue/capture` | `RevenueCaptureResponse` (record) | 201 OK | 201, unchanged |
| `POST /v1/revenue/commission-split` | `Map<String,Object>` | 201 OK | 201, unchanged |

**The reversal journal had the identical defect and is fixed in the same pass** — meaning the
refund/cancel reversal journal was also never being booked, on any cancelled payment. That is a second,
separately-consequential instance of the same bug that nobody had noticed either.

Every remaining revenue-ledger endpoint was checked by inspection and returns a record
(`TrialBalanceView`, `RevenueJournalReconciliationView`, `RevenueSummaryResponse`, `JournalPage` /
`JournalView`) or a `Map` — none is affected.

**Second caller, cured for free:** settlement-reconciliation's per-batch
`RestRoundingResidualClient` posts to the same residual endpoint, so **per-batch settlement residuals
were 406ing too**. It reads `ResponseEntity<Void>` and ignores the body, so the server-side fix cures it
with no client change and the new body breaks nothing.

---

## 5. Drain implications — is a backfill needed?

**Read-only review of `payment-executor`'s T2-5 replay path. Short answer: the endpoint works now, but
the replay job will not necessarily self-heal the rows already accumulated — a backfill is likely
needed.**

What works: `RestRevenuePostingReplayClient` maps `ROUNDING_RESIDUAL` → `/v1/journals/rounding-residual`
and re-POSTs the **stored payload verbatim** as a JSON string with an explicit `Content-Type`. The
payload shape (`{reference, residual, currency}`) is exactly what the endpoint accepts, the endpoint is
idempotent on `reference` (`rounding_residual_keys`, Flyway V006), and the client counts 201 as `POSTED`
and 200/204 as `ALREADY_PRESENT` — both "landed". So a `PENDING` row replays cleanly and safely once the
fix is deployed.

**The catch — 406 is classified as a permanent rejection.** `isRetryableStatus` is `>= 500 || 408 || 429`,
so a 406 becomes `permanentRejection`, and `RevenuePostingReplayService.sweepOnce` calls `row.poison(...)`
on the **first** sweep. `POISON` is terminal by design ("A POISON row is never retried again — that is
the point"), and **there is no requeue/unpoison path anywhere in the service** (the ops surface at
`/internal/ops/revenue-posting-failures` exposes `GET`, `GET /rows` and `POST /replay`, but nothing that
resurrects a terminal row).

So the state of the accumulated residuals depends on whether a sweep has run:

- **Never swept → still `PENDING`.** They will replay automatically once the fix is deployed and the
  scheduler runs, or immediately via `POST /internal/ops/revenue-posting-failures/replay`. No backfill
  beyond that. **This is the likely case on the measured fleet**, because
  `RevenuePostingReplayScheduler` is `@ConditionalOnProperty(gmepay.revenue-posting-replay.enabled,
  havingValue="true")` — opt-in, default off.
- **Already swept → `POISON`, terminal.** These need a **manual requeue** before any replay will touch
  them: set `status='PENDING'`, `attempts=0`, `next_attempt_at=now()` on
  `revenue_posting_failures` where `posting_type IN ('ROUNDING_RESIDUAL','REVERSAL_JOURNAL')` and
  `last_error` mentions `HTTP 406`. Then trigger a sweep.

**Operator step before anything else:** query
`GET /internal/ops/revenue-posting-failures/rows?status=POISON` (and `?status=PENDING`) to see which case
applies. The count should be ~1 per payment processed since the defect appeared.

Note the replay lands the journal but does **not** retroactively fix any period already closed off the
un-booked numbers — `GET /v1/revenue/journal-reconciliation` and `GET /v1/journals/trial-balance` will
shift by the total residual once the drain completes.

---

## 6. Follow-ups for other owners (not done here)

1. **`payment-executor` (concurrently owned) — POISON rows are unrecoverable.** A server-side bug that
   returns a non-retryable 4xx permanently kills the replay row on first sweep, with no operator path
   back. Either add a requeue action to `RevenuePostingReplayController`, or stop treating 406 as
   permanent (a 406 is a server-capability answer, not a verdict on the payload — unlike a 400). This is
   the reason this defect is potentially unrecoverable rather than merely delayed.
2. **`e2e-tests` (out of scope) — `WalletScanPayE2ETest` asserts only the *fee* journal.** That is the
   assertion gap that let 200 real payments run with no residual journal. It should assert the residual
   journal too, and the per-txn row count should drop 12 → **11** in
   `Documentation/CAPACITY_AND_SLA.md` once the drain completes.
3. **No client change is needed** for this defect. Listed explicitly because the task asked for the
   precise client change if the client were the culprit: it is not.

---

## 7. Verification

```
gradlew.bat :services:revenue-ledger:test     BUILD SUCCESSFUL   (RevenueLedgerHttpContractTest 8/8, 0 failures)
gradlew.bat testClasses                       BUILD SUCCESSFUL   (101 tasks)
```

Pre-fix, the same suite fails 3/8 with `Status expected:<200> but was:<406>`. All pre-existing
revenue-ledger tests stay green.

### Files

| File | Change |
|---|---|
| `services/revenue-ledger/.../web/JournalResponse.java` | **new** — the serializable wire DTO |
| `services/revenue-ledger/.../web/RoundingResidualController.java` | returns `JournalResponse` from both POSTs; javadoc |
| `services/revenue-ledger/.../web/RevenueLedgerHttpContractTest.java` | **new** — `@WebMvcTest` slice, 8 tests, 4 endpoints |
| `services/revenue-ledger/CHANGELOG.md` | T3-12 entry |
| `Documentation/GAP_REGISTER.md` | T3-12 under *Newly discovered while fixing* |
