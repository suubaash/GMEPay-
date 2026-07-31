> 작업: OpsAlertStore + operator-audit stub + idempotency claim / 출처: agent

# The last replica ceiling is gone, the last always-on stub is gone, and the create path can no longer double-write

**Branch:** `feat/exec-gap-closure-2026-07-28` · **Touched:** `services/ops-partner-bff`,
`services/transaction-mgmt`, `docker-compose.yml`, `deploy/helm/gmepay/values{,-aws,-azure}.yaml`,
`Documentation/GAP_REGISTER.md`, two CHANGELOGs, this report.

No Docker was started. No server was started. No second replica has ever actually existed.

---

## 0. The answer, first

| Item | State | What makes it true |
|---|---|---|
| **1. `OpsAlertStore`** | **CLOSED** | The BFF's own `ops_alerts` table (V001). Alerts, acks **and alert ids** are now fleet-wide |
| **2. `StubOperatorActionAuditClient`** | **CLOSED — but not by flipping to `rest`** | The default is a new durable local table (V002). The `rest` endpoint **does not exist**; §2 |
| **3. `POST /v1/transactions` double-create** | **CLOSED** | Claim-first (V014) + the unique index the javadoc had been promising (V015) |

**Replica ceiling, every service:** api-gateway N>1 · transaction-mgmt N>1 · **ops-partner-bff N>1**
· payment-executor / settlement-reconciliation / notification-webhook / scheme-adapter-zeropay /
revenue-ledger / prefunding N>1. **No service is held at 1 replica by in-process state any more.**
Three things that are *not* ceilings and are unchanged: `values.yaml` still ships `replicas: 1`
everywhere and there is no HPA (this work makes N>1 *safe*, it does not turn it on); Kafka consumers
gain nothing until the topics are repartitioned, and ops-partner-bff's consumer factory still never
calls `setConcurrency`; and five of the six `Stub*`/in-memory findings from the previous sweep are
still only safe because a values file happens to wire `rest`.

---

## 1. `OpsAlertStore` — a table, because Redis was the wrong shape

The previous agent declined to move this to Redis and gave the reason; I built what it said to build
rather than re-deriving it. What that produced:

| Piece | Detail |
|---|---|
| `V001__create_ops_alerts.sql` | `seq BIGSERIAL` PK + classification + `detail` + the paging record + the ack. **First table this service has ever owned** — the module comment that said "does NOT connect to any database" was corrected rather than left lying |
| `V002__create_operator_action_audit.sql` | §2 |
| `V003__create_shedlock.sql` | One locked job (retention). The migration itself records why the *other* job must not be locked |
| `OpsAlertStore` → port | `JpaOpsAlertStore` (default) / `InMemoryOpsAlertStore`. `gmepay.ops.alerts.store` = `db` \| `memory`; anything else **refuses to start** |
| `OpsAlertRetentionSweeper` | `gmepay.ops.alerts.retention-days`, default **90** — same property name and default as payment-executor's emitter-side pruner, so the two halves of one alert's history age out together. **Engineering default, not a business commitment** (§6) |

### 1.1 The defect nobody had written down: the alert **ids** collided

The register recorded a divergent list and a divergent ack. It missed the worse one. `seq` came from a
per-JVM `AtomicLong` starting at **1** on every replica and every restart, and `seq` is the path
variable of `POST /v1/admin/ops/alerts/{id}/ack`. At N>1 an operator could acknowledge **a different
alert than the one they clicked** — and the ack is audited, so the audit record would name the wrong
alert. One database sequence mints them now.

### 1.2 Why the table is not just "Redis with extra steps"

`update(seq, mutator)` is the read-modify-write the previous agent named: the paging stamp is written
by the Kafka listener thread and the escalation sweep, the ack by a request thread, possibly on
another replica. On a table that is `findByIdForUpdate` (`SELECT … FOR UPDATE`) inside a transaction,
so the two writers serialise. A two-thread test asserts both fields survive; without the lock the
later writer's copy-back would drop the earlier one's, and the visible symptom is *an alert that
displays as never-paged after someone acks it*.

**Explicit `TransactionTemplate`s, not `@Transactional`** — and this is not style. The transaction
boundary here is a **correctness** requirement (the row lock must be held until the copy-back
flushes), and `@Transactional` only applies through a Spring proxy: it silently does nothing when the
store is constructed directly, and self-invocation bypasses it anyway. The first version of this class
did use the annotation and the tests failed with `TransactionRequiredException` — which is the good
outcome, but in production the annotated version would have *worked* (proxied `@Bean`) while being one
refactor away from silently losing the lock.

### 1.3 Two decisions about failure, in opposite directions

- **`add()` never throws.** It is called immediately before the dispatcher pages a human. A DB hiccup
  propagating would abort the Kafka record *before the page went out* — turning a storage failure into
  a **missed page**. So a failed insert is logged at ERROR with the whole alert and a transient view
  (`seq = 0`) is returned; the dispatcher's existing `orElse(alert.withPaging(p))` fallback then stamps
  in memory and paging proceeds untouched. Same posture, same reasoning, as payment-executor's
  `OpsAlertArchive#record`.
- **Reads propagate.** An operator surface that answers "no alerts" because its store is unreachable is
  a lie a human acts on during an incident. 500 is the honest answer. The escalation sweep sees the
  same exception, logs it, and retries next tick — nothing is silenced permanently.

`management.health.db.enabled=false`, and this one is a genuine judgement call rather than an obvious
one, so it is written down in the properties file: the datastore backs **2 of ~40** BFF surfaces (the
rest are proxies to upstreams and keep working), K8s readiness has no "partial", and contributing DOWN
would take the whole Admin UI and Partner Portal offline to protect the alerts page. Both affected
surfaces already fail loudly and fail closed on their own, so nothing is hidden — only the blast radius
is bounded. Same shape as the Redis decision the previous agent made, for the same reason.

### 1.4 The anti-lock property, preserved and now **pinned by a test**

The escalation sweep stays un-ShedLocked. The correction to the class comment and the Helm comment is
preserved, and the register's history now shows *both* the old reason and the new one, because the
justification genuinely shifted and pretending otherwise would be the same kind of stale comment this
work exists to fix:

- **Old reason (no longer applies):** the buffer was per-replica, so a lock would leave every *other*
  replica's un-acked CRITICALs never escalated. The store is shared now, so any one replica sees them
  all.
- **Reason it still holds:** **a lock can only ever subtract escalations.** A stuck lock row, an
  unavailable lock provider, or a `lockAtMostFor` shorter than the incident means *no* replica sweeps —
  the lock becomes a new way to silence the pager. And the duplicate it would prevent is already
  prevented in the right place: at the pager, by the shared `PagingCooldown` claimed atomically before
  each page.

`EscalationSweepAcrossReplicasTest` asserts `sweep()` carries **no** `@SchedulerLock` (the failure
message says why, ending "if you are here because you added a lock: don't") **and** that
`OpsAlertRetentionSweeper#prune()` *does* — so the absence cannot read as laziness. This service now
has a `LockProvider`, which is exactly the condition the old note said to wait for, so the decision had
to become enforceable rather than advisory.

The sweep's query changed from `recent("CRITICAL", null, 0)` — the old "0 = unlimited" — to an explicit
`MAX_LIMIT` (500). Against a table, unlimited means fetching the whole retention window into the heap
every 60 seconds. 500 still-open CRITICAL alerts is itself a catastrophe, and the newest are swept first.

### 1.5 Two column choices that are about not losing alerts

- **`occurred_at` is `VARCHAR`, not `TIMESTAMP`.** It is the producer's own string, and the escalation
  sweep has always carried an explicit non-ISO fallback path — so some producer does not send an
  instant. Parsing it here would either reject the alert or silently rewrite what the producer said.
  Ordering and retention use `seq` and `created_at` (our clock) instead. `seq DESC` is insertion order,
  i.e. byte-for-byte the ordering the deque gave.
- **No CHECK on `severity`**, unlike payment-executor's `ops_alerts`. There it is right — that service
  *emits* and owns the vocabulary. Here we consume arbitrary producers, and a CHECK would turn an
  unexpected spelling into a rejected insert: a dropped alert, on the path whose entire purpose is that
  alerts stop being dropped. Over-long fields truncate and missing classification becomes `UNKNOWN` for
  the same reason.

---

## 2. The operator-action audit stub — and why the T1-1 fix could not be applied literally

The defect is exactly the T1-1 class: `@ConditionalOnProperty(matchIfMissing = true)` on a `Stub*`
bean whose selector is set in **no** values file, no compose service and no properties file. So an
in-memory `CopyOnWriteArrayList` with an `AtomicLong` was the operator-action audit trail **in every
environment**: colliding `OA-n` ids at N>1, gone on restart, and — the part that matters most —
`recordDurable()` **could not fail**, so "no money-affecting operator action without a durable audit
record" was decorative rather than enforced.

**T1-1's fix is "invert the default so the real client wins". T1-1's *first step* is "check the real
client's endpoint is real". That step is what stopped the inversion here.**

`RestOperatorActionAuditClient` POSTs `auth-identity POST /v1/audit/operator-actions`. **No service in
this repository exposes it** — grep-verified: the only `/v1/audit` surfaces are config-registry's
*read* endpoints (`AuditLogController`, `AuditIntegrityController`), and auth-identity's audit package
has no such controller at all. Flipping the default to `rest` would have made every audited operator
action 500 through the fail-closed path — the console would have looked broken the moment anyone tried
to pause the platform.

So the real implementation is the one that can exist today: **write the record here, durably.** That is
precisely the move payment-executor made for the emitter half of T3-3 (`ops_alerts`, V006) while the
consumer side was not real yet — persist where it is raised rather than depend on infrastructure that
does not exist. `DbOperatorActionAuditClient` writes `operator_action_audit` (V002) in its **own**
transaction (`REQUIRES_NEW`, via a `TransactionTemplate` because self-invocation bypasses the proxy),
so the record cannot be rolled back together with the action it audits.

| `gmepay.operator-action-audit.client` | Result |
|---|---|
| **absent (the inversion)** / `db` | `DbOperatorActionAuditClient` — the durable table |
| `stub` | The stub, **plus a `WARN` at construction** naming every consequence: heap-only, ids restart at 1 per replica so they collide, lost on restart, and `recordDurable()` cannot fail |
| `rest` | Still selectable, for the day the endpoint ships. Its javadoc and the compose/Helm comments both say plainly that it does not exist yet |
| anything else | **No bean at all** ⇒ `OpsAlertAckController`'s required constructor arg fails context refresh ⇒ the service **refuses to boot** |

Set explicitly to `db` in compose and `values.yaml` anyway — belt and braces, because "the selector was
set nowhere" is the entire defect.

**A real bug was found while proving this.** The context-runner test failed with `NoSuchMethodException`
because `DbOperatorActionAuditClient` has two constructors and neither was `@Autowired`: Spring could
not pick one, fell back to a no-arg constructor that does not exist, and the bean failed. That is the
same two-constructor trap already recorded for the `Rest*Client` adapters, and it would have been a
boot failure in production. Fixed with `@Autowired` on the production constructor.

**What this does NOT close, stated plainly.** These rows are a **flat append-only log**, not
config-registry's hash-chained `audit_log`. They prove *what an operator did*; they do not prove nobody
edited the table afterwards. Moving them behind a config-registry write endpoint (and then genuinely
selecting `rest`) is recorded as the follow-up in the register, in V002's header, and in the interface
javadoc.

---

## 3. `POST /v1/transactions` — claim first, and the constraint the javadoc promised

### 3.1 The ordering fix

`IdempotencyStore` is now a claim-first protocol and `putIfAbsent` is **gone** — it could only ever be
called after the thing it was supposed to guard already existed.

```
Claim c = store.claim(key);            // an INSERT of a RESERVED row: the PK picks the winner
  REPLAY    -> 200, the winner's snapshot, byte-for-byte
  IN_FLIGHT -> 409 IDEMPOTENCY_CONFLICT, and NOTHING is created
  CLAIMED   -> create; on failure release(key) and rethrow; on success complete(key, snapshot) -> 201
```

409 is a new status on this endpoint and it is the honest one. The alternatives are "201 plus a second
transaction" (the defect) and "200 with a response that does not exist yet" (a lie).
`IDEMPOTENCY_CONFLICT` already existed in `lib-errors` (409) and is reused rather than adding an enum
constant to a shared library on this diff.

### 3.2 The money-safety column, which is the part that could have gone wrong

A claim with no expiry is a **worse** bug than the one being fixed. If a claimant is evicted between
claiming and completing, every retry gets 409 for the whole 24-hour replay window — and a payment that
can never be retried is a **lost** payment. So `claim_expires_at` (V014) lapses a `RESERVED` claim
(`gmepay.idempotency.claim-ttl`, engineering default **2 minutes**, §6) and the next caller takes it
over.

That reclaim carries a small residual risk on purpose: if the dead claimant *had* already inserted its
transaction, the reclaiming retry creates a second one. **That is what V015 catches.** The two halves
were designed together, and neither is sufficient alone.

Other deliberate details: `response_snapshot` stays `NOT NULL` with `''` on a `RESERVED` row rather
than the column being made nullable (`ALTER COLUMN … DROP NOT NULL` is not portable across PostgreSQL
and H2-in-PG-mode; `state` is the authoritative discriminator and `get()` only ever returns a snapshot
for a `COMPLETED` row, so the empty string is never observable). `complete()` on a lapsed claim logs a
WARN and does **not** throw — the transaction exists and its caller is owed a 201. A failed
`complete()` likewise cannot cost the caller the response to the transaction it just created.

### 3.3 The constraint that had been promised for months

`ux_transactions_partner_txn_ref` on `transactions (partner_id, partner_txn_ref)` (V015). The javadoc
claim the previous agent found and corrected is now **true**, and the reason to make it true rather
than only correct the text is worse than "belt and braces":

**payment-executor's `RestTransactionClient.createPending` sends no `Idempotency-Key` header at all**
(grep-verified). The header is enforced only at the partner edge, by api-gateway's
`IdempotencyKeyFilter`. So on the primary *internal* money path the idempotency table is never
consulted, and **this index is the only duplicate suppression that exists there**. A money path with no
database-level uniqueness was depending entirely on application code being reached.

- Legacy rows (the 5-field create path leaves both columns NULL) are unconstrained — PostgreSQL and H2
  both treat NULLs as distinct in a unique index. Asserted by a test, because "the migration broke the
  legacy path" is the obvious way this goes wrong.
- Scoped to the partner: two partners may both call their reference `INV-1`. Asserted.
- **If a database already holds duplicate pairs — exactly what create-before-claim produced — the
  migration FAILS and the deploy stops.** That is correct for duplicate money rows: they need a human.
  The migration carries the detection query. No production data exists today.

### 3.4 Callers

Unaffected. The `Idempotency-Key` contract (including the 200-vs-201 replay distinction) is unchanged,
and payment-executor cannot receive the new 409 because it sends no key — so nothing downstream needed
to learn a new status on this diff.

---

## 4. Verification

| Check | Result |
|---|---|
| `:services:ops-partner-bff:test` | **529** tests, 0 failures, 0 errors, 0 skipped (was 487; **+42**) |
| `:services:transaction-mgmt:test` | **191** tests, 0 failures, 0 errors, 0 skipped (was 176; **+15**) |
| `gradlew testClasses` (whole repo) | **BUILD SUCCESSFUL** |
| `check_internal_auth_wiring.py` | **95/95** |
| `check_monitoring_wiring.py` | **37/37** |
| `check_helm_chart_wiring.py` | **199/199** (was 194 — the +5 are this service's new datasource requirements, derived from the code, not added by hand) |
| `check_gitleaks_config.py` | findings=0, missed=0, false-positives=0, re2-bad=0 |
| `check_load_harness_wiring.py` | OK |
| `node docker/keycloak/check-topology.mjs` | **101/101** |
| PyYAML parse | `docker-compose.yml` + all four Helm values files parse as dicts; asserted programmatically that the BFF has a real `SPRING_DATASOURCE_URL` + the credential pair in `envSecretKeys` + `GMEPAY_OPERATOR_ACTION_AUDIT_CLIENT=db`, that `postgres-bff` and its volume exist, and that **host ports are unique** |

### 4.1 New tests, and what each actually proves

| File | What it proves |
|---|---|
| `ops-partner-bff/.../alert/JpaOpsAlertStoreTest` | Real H2 + **full Flyway set** (V001–V003 proved to apply and produce the expected columns). An alert stored on A is in B's list **with the same seq** — and two per-JVM stores show B an empty list **and mint a colliding seq=1**. Survives a "restart"; an ack on A is visible on B (per-JVM: invisible). A paging stamp and an ack from different replicas both survive, **including from two concurrent threads**. `add()` never throws on a broken repository; reads do. A non-ISO `occurredAt` is stored verbatim; a producer omitting severity still gets its alert stored. Limits clamp; the pruner deletes only lapsed rows and is idempotent |
| `ops-partner-bff/.../alert/OpsAlertStoreConfigTest` | The decision table: default = durable; `memory` explicit; **unknown values (incl. `redis`, `database`, `inmemory`, blank) refuse to start**; `db` over in-memory H2 is still selected (the WARN is the signal); the shipped `application.properties` |
| `ops-partner-bff/.../alert/paging/EscalationSweepAcrossReplicasTest` | **The anti-lock property.** B's sweep escalates an alert only A consumed — and with two per-JVM stores it escalates **nothing**. An ack on A stops B. Two replicas both sweeping page **once** through a shared cooldown. `sweep()` has **no** `@SchedulerLock`; `prune()` does |
| `ops-partner-bff/.../client/OperatorActionAuditClientSelectionTest` | No property ⇒ the durable client; `stub` only by name; `rest` still selectable; **six unknown values leave no bean and a consumer fails to start**; exactly one implementation is ever registered; the shipped properties select `db` and no longer contain the old `:stub` default |
| `ops-partner-bff/.../client/db/DbOperatorActionAuditClientTest` | Two "replicas" mint **distinct** ids — and two stubs both mint `OA-1` for different actions. The row reads back verbatim; nulls become `unknown` rather than a constraint violation; `recordDurable` **throws** when the write fails (the stub could never reach that); `record` is best-effort with a null id |
| `transaction-mgmt/.../api/TransactionCreateDuplicateSuppressionTest` | **Two real threads, one key: exactly one 201, one 409 `IDEMPOTENCY_CONFLICT`, and exactly ONE row** (it was 2). The 409 is retryable — the same key later replays with 200 and the same `txnRef`. A failed create releases the claim. No key ⇒ unchanged 201 and an untouched store. **The unique index rejects a duplicate with the store not involved at all**, is scoped to the partner, and leaves legacy NULL rows alone |
| `transaction-mgmt/.../idempotency/JdbcIdempotencyStoreTest` (rewritten) | 8 concurrent claimants ⇒ **1 CLAIMED + 7 IN_FLIGHT** (before V014 all 8 had already created); `IN_FLIGHT` becomes `REPLAY` once the winner completes; a `RESERVED` key has no snapshot to give; `release` frees it immediately but never deletes a completed key; **a lapsed claim is reclaimable** and completing a lapsed claim does not throw; expiry on read; the CHECK constraint rejects a third state; **pre-V014 rows default to `COMPLETED`** so the migration did not orphan the existing window |

### 4.2 What the tests do NOT prove — said plainly

**No second JVM and no PostgreSQL were ever involved.** There is no Docker on this machine (every
Testcontainers test in the repo is `@Tag("docker")` and CI-only), so "two replicas" is two store
instances over one **real H2 (PostgreSQL mode) database with the real Flyway DDL** — the stronger of
the two instruments the previous agent used, and it is the right one for the property under test
("the store keeps no state in the JVM"). It proves nothing about PostgreSQL's `SELECT … FOR UPDATE`
semantics versus H2's, about Hikari pool behaviour under real contention, or about the V015 index on a
table that already has rows. The concurrency tests use **real threads against that real database**, not
mocks, which is what the task required and what a mock cannot show.

Each cross-replica assertion is paired with the per-JVM version getting it **wrong**. Without that
half, a green test shows only that the new code runs — not that the defect it was written for existed.

---

## 5. Found while doing this (not fixed)

1. **`docker-compose.yml` publishes host port `8092` twice** — `scheme-adapter-nepal` and
   `settlement-reconciliation`. `docker compose up` fails for whichever binds second. **Pre-existing**
   (confirmed present at HEAD `ceabc9c`), found only because I added a PyYAML host-port uniqueness
   assertion while placing `postgres-bff`. Not fixed here: it is unrelated to these three items and
   picking which service moves affects `run-fleet.ps1`, the e2e fleet and the docs. A background task
   was filed, including "add the uniqueness assertion to `check_helm_chart_wiring.py`" so it cannot
   come back silently. **Ports 5433–5447 are all taken**, so the new datastore is on 5448.
2. **`auth-identity` has no operator-action audit endpoint** and, as far as this repo shows, no plan
   for one. Either config-registry's hash-chained `audit_log` gains a write endpoint (the better
   answer — it is already the regulator-facing surface, with chain verification) or
   `RestOperatorActionAuditClient` should eventually be deleted rather than left as selectable dead
   wiring. Recorded, not decided.
3. **The BFF now needs a database that does not exist yet in any real environment.** `postgres-bff` is
   new in compose; the AWS/Azure overlays point at placeholder managed instances. A deploy that
   upgrades the chart without creating the `bff` database will fail at Flyway — loudly, which is the
   right failure, but it is a **provisioning step**, not a values edit.
4. **`OpsAlertKafkaConsumerConfig` still never calls `setConcurrency`** (T3-11 follow-up 3, and the
   sole remaining entry in the fleet-wide guard's baseline). Still one line, still open: it is a Kafka
   throughput item and worthless until the topics are repartitioned, so it does not belong in a
   correctness diff.
5. **`ClientBeans.patchCapableRequestFactoryCustomizer`** (T3-11 follow-up 4) is still there.
   Untouched for the same reason as last time: it is a timeout concern.

---

## 6. Numbers an owner must confirm (none of these is a business commitment)

| Setting | Engineering default | Why it needs an owner |
|---|---|---|
| `gmepay.ops.alerts.retention-days` | **90** | How long operational alert evidence **and the operator acks on it** must be kept is a records-retention decision, not an engineering one. 90 mirrors payment-executor's emitter side so the two halves of one alert's history age out together — that consistency is the only thing engineering can defend |
| `gmepay.idempotency.claim-ttl` | **2 minutes** | Must comfortably exceed p99 create latency. Too short ⇒ a slow-but-alive claimant is overtaken (duplicate, caught by V015). Too long ⇒ a retry after a pod death waits that long for its 409 to become a 201. Confirm against measured latency once the load harness has run |
| `gmepay.ops.alerts.prune-interval-ms` | 6h | Pure housekeeping cadence; low stakes, listed for completeness |

Nothing here invented an SLO, a threshold with a business meaning, or a retention period presented as
a commitment.
