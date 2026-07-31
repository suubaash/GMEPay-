> 작업: replica ceiling / 출처: agent

# The single-replica ceiling is lifted — for every service except one, and that one is named

**Branch:** `feat/exec-gap-closure-2026-07-28` · **Touched:** `services/api-gateway`,
`services/transaction-mgmt`, `services/ops-partner-bff`, `docker-compose.yml`, `deploy/helm/**`,
`Documentation/GAP_REGISTER.md`, three CHANGELOGs, this report. **`libs/**` was not touched** — see
§7 for why no shared client belongs there.

No Docker was started. No server was started. No second replica has ever actually existed.

---

## 0. The answer, first

| Service | N>1? | What makes it safe |
|---|---|---|
| **api-gateway** | **Yes** | Rate-limit window + replay nonce set in Redis. **Redis required** — fail-closed by default |
| **transaction-mgmt** | **Yes** | Idempotency keys in its own durable table (V013), not Redis, not a map |
| payment-executor, settlement-reconciliation, notification-webhook, scheme-adapter-zeropay, revenue-ledger, prefunding | **Yes** for scheduler correctness | T3-11 + the ShedLock follow-up (unchanged by this work) |
| **ops-partner-bff** | **Paging: yes. Alerts read model: NO — keep it at 1** | Shared paging cooldown fixes duplicate/N× paging; `OpsAlertStore` is still per-JVM, deliberately (§4.3) |

**So the fleet-wide answer changes from 1 to N>1, with one named exception that is an operator
surface rather than a money path.** Two caveats that are not about state and are unchanged: Kafka
consumers gain nothing from added replicas until the topics are repartitioned (T3-11 §4), and three
consumer factories still never call `setConcurrency`.

---

## 1. api-gateway — the two controls that were *wrong* above one replica

The framing matters, and it is the reason this was worth doing before any scaling requirement
existed. Neither of these was an un-scaled control; both were **incorrect** at N>1:

- **Rate limit:** N replicas enforced N × the configured cap. The control exists to bound
  credential-stuffing and enumeration, so an attacker spreading traffic across replicas got exactly
  N times the budget the operator wrote down.
- **Replay:** a captured signed request was replayable **once per replica** inside the 5-minute
  clock-skew window. That is an integrity defect: the guarantee scaled *down* with the deployment.

### 1.1 Rate limit — why window-indexed `INCR` and not a Lua script

```
index = floor(now / windowMillis)          // aligned; every replica agrees, no coordination
key   = gw:rl:{partnerId}:{scope}:{index}
count = INCR key                            // one atomic server-side op — the whole algorithm
if count == 1 -> EXPIRE key (2 x window)
```

A fixed window that stores its start time in the *value* needs a read-modify-write, hence a Lua
script or a lock. Putting the window number in the **key** makes `INCR` sufficient. The property
that decided it is the failure mode: because the key changes every window, **a failed `EXPIRE` can
only leak one key for one window** — it can never pin a partner at its limit forever, which is what
"INCR then hope" would risk. Clock skew between replicas shifts a partner's boundary by the skew,
which at worst lets a burst straddle two windows (the ordinary fixed-window property); it does not
multiply the cap.

`X-RateLimit-Remaining` is now computed from the shared count, which matters because it is a
partner-visible number and per-replica headroom was a lie.

### 1.2 Replay — `SET NX EX`, and the two non-permissive readings

One command decides: Redis picks the winner server-side and the TTL is attached atomically, so a
nonce is never recorded without an expiry (which would reject legitimate reuse after the skew
window). Two deliberate readings:

- **An empty reply is NOT fresh.** "I do not know whether this nonce is new" must not resolve to
  "accept".
- **An over-long `X-Nonce` is 400 `INVALID_NONCE`** (`max-nonce-length`, default 256 — ~7× a UUID).
  A shared store turns an unbounded partner-supplied header into an unbounded write into
  infrastructure shared with every other tenant of this Redis. The in-memory store had the same
  exposure, but the blast radius was one pod's heap.

### 1.3 One switch, not two

`gateway.shared-state.store` = `auto` (default) | `redis` | `memory`, governing **both** stores.

Per-control switches would be more flexible and that is the problem: the configuration nobody wants
and anybody could produce with two keys is the **half-shared** one — global nonces, per-pod rate
window. There is no deployment in which that is the intent.

| Value | Behaviour |
|---|---|
| `auto` (default) | Redis when `spring.data.redis.host` is set, memory otherwise. Logs which, and the resulting replica ceiling |
| `redis` | Redis, and **refuse to start** without a host |
| `memory` | Per-JVM. Legal, logged `WARN` naming the N=1 ceiling |
| anything else | **Refuse to start** |

The refuse-to-start cases are the point. A gateway that silently falls back to a
`ConcurrentHashMap` when its Redis host is missing looks healthy, serves traffic, and enforces N×
the cap while accepting one replay per replica — a failure with no symptom until someone replays a
captured request. Same idiom and same `refuses to start` prefix as T0-7's
`gateway.partner-credentials.source`.

`auto` resolves to Redis in every deployed environment (compose and all four Helm values already
export `SPRING_DATA_REDIS_HOST` for this service) and to memory on a laptop. **No new
infrastructure was added** — `redis:7-alpine` was already in compose at line 273 and the Helm ABI
ConfigMap already declared the variables.

`InMemoryRateLimitStore` and `InMemoryNonceStore` lost their `@Component @Primary
@ConditionalOnProperty` annotations. Those pointed at a property nothing set and an alternative that
did not exist — a switch that looked like a choice. Wiring now lives in one class, the same
correction T0-7 applied to `ConfigPartnerCredentialService`.

---

## 2. The guardrail: "Redis is down" was decided, not defaulted into

This is the part of the task that could most easily have gone wrong quietly, so here is each
decision and its reasoning.

### 2.1 Rate limit — default unchanged, and a third option that a boolean could not express

`gateway.rate-limit.on-store-error`:

| Value | Behaviour | Why it exists |
|---|---|---|
| **`deny` (default)** | 429 | **Identical to T0-7's `fail-open: false`.** An outage in the control that bounds enumeration must not hand an attacker unlimited attempts. Chosen as the default because it is the posture T0-7 set deliberately, and swapping the storage layer is not a mandate to quietly relax it |
| `local` | Degrade to the per-JVM window | The cap becomes N × the value instead of disappearing — **exactly the pre-Redis posture**. This is the setting to use in a multi-replica deployment that judges a Redis outage worse than an N × cap. It is the answer that did not exist while a boolean was the only knob: "not fail-open" could only mean "deny", with no way to say "degrade" |
| `allow` | No rate limiting during the outage | Offered only because `fail-open: true` already meant this, and silently re-interpreting an existing deployment's configuration would itself be a posture change |

`fail-open: true` still maps to `allow` and outranks `on-store-error`, so T0-7's tests and any
deployment that set it keep their meaning. The shipped default is `fail-open: false` +
`on-store-error: deny` — i.e. byte-for-byte the T0-7 behaviour.

The `local` fallback is a **real counter**, not a rubber stamp: a test drives three requests through
a broken Redis with a local cap of 2 and asserts the third is still refused.

### 2.2 Replay — there is deliberately no `allow`

`gateway.replay-protection.on-store-error` = **`reject`** (default) | `local`. That is the whole
enum, and a test asserts the enum has exactly two values.

A replay check that fails open is not a replay check — it is a control an attacker can disable by
making one Redis unreachable, which is strictly easier than forging an HMAC signature. So the two
answers offered are "no verdict, therefore no forwarding" (503 `REPLAY_STORE_UNAVAILABLE`) and
"degrade to the per-JVM check" (one replay per replica — the pre-Redis posture). An unrecognised
value fails property binding and the service refuses to start, rather than resolving to something
permissive.

**This also fixed a latent defect.** `ReplayProtectionFilter` had *no* error handling, because its
store was a map and could not fail. A Redis error would have escaped the reactive pipeline as a bare
**500** — which T0-7 explicitly converted to 503 everywhere else at this edge. The `onErrorResume`
is scoped to the replay decision only (the outcome is materialised into an enum *before* the chain is
invoked), so a downstream error is not swallowed and no verdict is written onto an already-committed
response. That is the placement rule T0-7 established for the three credential-resolving filters.

### 2.3 The consequence, stated rather than discovered

**Under the shipped defaults, Redis is a hard dependency of the partner edge.** A Redis outage
answers partner traffic 503 (replay) and 429 (rate limit). The gateway also already ships
`management.health.redis.enabled: true`, so the pods additionally leave the readiness rotation.

That is the correct reading of "fail closed" and it is not hidden: all four Helm values files now
carry an operator note — ElastiCache multi-AZ with automatic failover, Azure Standard-or-above
(Basic is a single node with no SLA, so a maintenance restart would 503 the edge), Sentinel/HA
on-prem — **or** set `on-store-error=local` knowingly. The right lever is named next to the right
platform.

### 2.4 ops-partner-bff paging — the deliberate inverse, and why

The same question for the paging dedupe cooldown gets the **opposite** answer, and the contrast is
the point:

- The gateway's controls **authorise traffic**. An unavailable authorisation must not become a
  permission ⇒ fail closed.
- The paging cooldown **authorises nothing**. Its only job is to stop a re-firing sweep from
  storming a pager. Failing closed would mean **suppressing pages while Redis is down** — an alert
  nobody hears, during an incident, which is the exact scenario the pager exists for.

So an unreachable Redis falls back to the **per-JVM** cooldown: dedupe degrades from fleet-wide to
per-replica (at worst N pages instead of 1) and **nothing is silenced**. A missed page is not
recoverable; a duplicate page is an annoyance.

**This is not configurable, and that is the decision.** There is no operational position from which
"silence the pager when its dedupe cache is unreachable" is right, so offering it as a flag would
only be a way to get it wrong. Every failover logs `WARN`, so the degradation is visible rather than
inferred from duplicate pages.

Correspondingly, `management.health.redis.enabled=false` on this service: marking every BFF replica
unready — taking the admin UI and the partner portal down — because a noise-reduction cache is
unreachable would be absurd.

---

## 3. transaction-mgmt — a table, not Redis, and the reasoning is the deliverable

The task asked me to pick Redis or a DB table "based on what the module already does for durability
and say why". I picked the table. Three reasons, in order of weight.

### 3.1 The previous state was worse than "per-JVM"

The register said the in-memory store gave a per-replica 24 h window. True under compose. **Under
Helm it was using Redis** — because `IdempotencyConfig` marked the Redis store `@Primary` whenever
`spring.data.redis.host` was set, and the Helm ABI ConfigMap exports that variable to **every pod for
api-gateway's benefit**. So this service's duplicate-money-transaction control was selected by an
environment variable belonging to a different service, and nobody chose either store.

And the Redis it selected is a **cache**: `redis:7-alpine`, no AOF, no replication. A Redis restart
emptied the 24 h window silently, so a partner retry after it created the second transaction anyway —
the same failure as the per-JVM map, just rarer and much harder to reproduce.

### 3.2 A table cannot be unavailable while the money path is available

This is the decisive argument and it is about the guardrail, not about durability alone. Putting the
key in Redis creates a control that can be **down while the thing it protects is up**, which forces a
fail-open/fail-closed choice on duplicate suppression where neither answer is good (fail open ⇒
duplicate transactions; fail closed ⇒ no transactions at all). Putting it in the same database as the
transaction it protects makes the question **disappear**: if the table cannot be read, no transaction
can be created either, so there is no window in which duplicates are possible.

### 3.3 It is what this module already does

transaction-mgmt does durability with PostgreSQL + Flyway + ShedLock. This is its idiom, not a new
one.

### 3.4 What was built

| Piece | Detail |
|---|---|
| `V013__create_idempotency_keys.sql` | `idempotency_key` PK, `response_snapshot`, `created_at`, `expires_at` + an index for the sweep. Engine-neutral, additive, `IF NOT EXISTS`. V001–V012 existed |
| `JdbcIdempotencyStore` | **The claim is the primary key.** `putIfAbsent` attempts the `INSERT` rather than reading first, so concurrent duplicates — same JVM or different replicas — resolve to exactly one winner with no application-level locking. A lapsed row for the same key is reaped first, so a 25-hour-later reuse is not answered with yesterday's response |
| Expiry | Enforced **on read** as well as swept. Deliberate: the sweeper is ShedLock-guarded and therefore skippable by design, and a retention job must never be what decides when a key stops being replayable |
| `IdempotencyRetentionSweeper` | Hourly, `@SchedulerLock`ed. A table has no TTL, and one row per idempotent create with no cleanup is how a money-path table reaches a hundred million rows. 4th job ⇒ `spring.task.scheduling.pool.size` **4 → 5**, which `SchedulerPoolSizeTest` forced |
| Selection | `gmepay.idempotency.store` = `db` (default) \| `memory`; anything else **refuses to start**. `redis` is specifically no longer a value, asserted by test, so the cache cannot return by accident |
| Deleted | `RedisIdempotencyStore`, `IdempotencyRedisStoreIT`, and `spring-boot-starter-data-redis` from `build.gradle` — which also removes a Redis health indicator that could have marked this service unready over infrastructure it no longer uses |

**Transaction context is load-bearing and documented.** These methods run *outside* any transaction:
`TransactionController#create` lets the transaction insert commit and then claims the key. That is
not incidental — on PostgreSQL a unique violation **aborts the enclosing transaction**, so a future
caller wrapping `putIfAbsent` in its own transaction would find every subsequent statement failing.
The javadoc says so and names `REQUIRES_NEW` as the fix. The test class runs
`@Transactional(propagation = NOT_SUPPORTED)` for the same reason.

### 3.5 A documentation defect found while doing this

`IdempotencyStore`'s javadoc claimed *"the DB unique constraint on the transaction key remains the
last-resort backstop"*. **No such constraint exists** — there is no `UNIQUE` anywhere in V001–V012
(grep-verified) and nothing a duplicate create would collide with (a second create mints a new
`txn_ref`). This store is the **only** duplicate suppression on the create path. Corrected in place,
because the false claim is exactly what would justify leaving the store in a cache.

---

## 4. ops-partner-bff

### 4.1 The paging fix, and the argument that was incomplete

`OpsPagingDispatcher`'s javadoc argued that paging is *"naturally single-fire across replicas: the
Kafka consumer group delivers each record to exactly one consumer"*. That is true of the consume path
and misses the two cases that actually page twice:

1. **The escalation sweep runs on every replica**, re-paging from that replica's own alert buffer
   against that replica's own cooldown map. N replicas, N escalation pages.
2. **A re-fired alert consumed by a different replica than last time** finds an empty cooldown map,
   well inside the 15-minute window.

The cooldown is now a shared `PagingCooldown` (`SET NX EX`), **claimed atomically before** the page
rather than checked-then-set. Check-then-set could not fix this: both replicas would read "clear" and
both would page. A failed delivery — or a throwing paging port — **releases** the claim, so the
pre-existing rule that *only a delivered page opens the cooldown* survives the change to an atomic
claim.

### 4.2 The escalation sweep is deliberately NOT locked — reversing the class's own note

`OpsPagingEscalationScheduler` was marked "single-replica-only; ShedLock it if the BFF ever gains a
DataSource". **Doing that would have shipped a worse bug than it closed.**

`OpsAlertStore` is a per-JVM buffer, so each replica holds a *different* set of alerts — whichever
its own Kafka consumer received. A distributed lock lets exactly one replica sweep, which means every
**other** replica's un-acked CRITICAL alerts would never be escalated at all. Un-acked alerts stop
escalating silently: a **missed page**, which is the failure the mechanism exists to prevent.

So the sweep runs on every replica, over its own buffer, and duplicate paging is prevented where it
belongs — at the pager, by the shared cooldown. Every replica may decide to escalate; at most one
succeeds per window. The note and the startup log now say this.

### 4.3 `OpsAlertStore` was NOT backed by shared state — precisely why, and precisely what it costs

**What it costs, stated first:**

- `GET /v1/admin/ops/alerts` returns a different list depending on which replica answers, and the
  control tower's counts are a fraction of the fleet's.
- An ack recorded on replica A is invisible on B, so an alert can look open after it was
  acknowledged — and B's escalation sweep keeps escalating it (bounded to one page per dedupe window
  by the shared cooldown, but it does not stop when a human acks).
- A restart loses the window entirely (pre-existing and already documented).

**Why Redis is the wrong answer here** — not effort, and not "it's only a display":

`update(seq, mutator)` is a read-modify-write over a record with two independently-written fields —
the paging stamp (Kafka thread + escalation sweep) and the ack (request thread). On a Redis hash that
needs `WATCH`/Lua optimistic concurrency, or **an ack silently overwrites a concurrent paging stamp
and the alert displays as never-paged**. Add `seq` allocation, capacity eviction and filtered
newest-first queries, and what is being described is a **table** — which is exactly the durable
JPA-backed store already recorded as this class's follow-up, and which would additionally fix restart
durability and give ack an audit trail. Building the Redis version first means building it twice and
shipping the weaker one, on an operator surface where a half-right implementation is worse than an
accurate limitation.

**So: run ops-partner-bff at 1 replica** until it has that store, or accept a divergent alerts view
knowingly. It is an operator-surface correctness defect, not a money one, and it constrains no other
service's ability to scale.

---

## 5. Verification

| Check | Result |
|---|---|
| `:services:api-gateway:test` | **176** tests, 0 failures, 0 errors, 0 skipped (was 143 at T0-7; **+33**) |
| `:services:transaction-mgmt:test` | **176** tests, 0 failures, 0 errors, 0 skipped (**+15**) |
| `:services:ops-partner-bff:test` | **487** tests, 0 failures, 0 errors, 0 skipped (**+11**) |
| `gradlew testClasses` (whole repo) | **BUILD SUCCESSFUL** |
| `check_internal_auth_wiring.py` | **95/95** |
| `check_monitoring_wiring.py` | **37/37** |
| `check_helm_chart_wiring.py` | **194/194** |
| `check_gitleaks_config.py` | findings=0, missed=0, false-positives=0, re2-bad=0 |
| `check_load_harness_wiring.py` | OK |
| `node docker/keycloak/check-topology.mjs` | **101/101** |
| PyYAML parse: `docker-compose.yml` + all four Helm values | all parse as dicts; asserted programmatically that api-gateway and ops-partner-bff have `SPRING_DATA_REDIS_HOST` + a `redis` dependency, that **transaction-mgmt has neither**, and that both services carry `SPRING_DATA_REDIS_PASSWORD` in `envSecretKeys` |

**T0-7 must not regress, and does not.** api-gateway's existing suite still covers the fail-closed
branches (`RateLimitFilterTest`'s fail-open/fail-closed pair, `PartnerEdgeFailClosedTest`'s
credential-store 503s). Both are untouched and green, and
`GatewaySharedStateConfigTest#shippedConfigIsTheSafeOne` additionally asserts the shipped
`application.yml` still carries `fail-open: false`, ships `on-store-error: deny`/`reject`, and
contains **no** `on-store-error: allow`.

### 5.1 New tests, and what each actually proves

| File | What it proves |
|---|---|
| `api-gateway/.../sharedstate/TwoReplicaSharedStateTest` | Two replicas sharing a store enforce **one** combined limit (alternating hits: 1–4 allowed, 5–6 refused at limit 4) — **and the same two on per-JVM stores admit all 6**. A nonce burned on A is rejected on B — **and per-JVM stores accept the replay on B**. Headroom reflects the shared count; the window rolls; the nonce expires and its key is reaped; a store failure errors rather than resolving to "allowed" |
| `api-gateway/.../sharedstate/GatewaySharedStateConfigTest` | The full decision table; `store=redis` without a host and any unrecognised value **refuse to start**; one switch drives both stores (the half-shared state is unreachable); Redis-selected-without-a-template refuses to start; the shipped `application.yml` |
| `api-gateway/.../filter/EdgeStoreUnavailablePostureTest` | Every posture branch: rate-limit DENY (default) / LOCAL (and that LOCAL is a real counter, not a rubber stamp) / LOCAL-without-a-fallback degrades to DENY / legacy `fail-open: true` still ALLOWs / ALLOW admits. Replay: the enum has exactly two values, REJECT is the default and answers 503, LOCAL still catches a same-pod replay, LOCAL-without-a-fallback rejects, an empty reply is not fresh, an over-long nonce is 400 **and never reaches the store** |
| `transaction-mgmt/.../idempotency/JdbcIdempotencyStoreTest` | Real H2 + **full Flyway set** (V013 proved to apply on V001–V012 and to produce the expected columns): a key honoured on A is replayed on B — **and two per-JVM stores re-execute it and mint a second txnRef**; **8 concurrent claimants across "replicas" yield exactly one winner** and every loser replays the same snapshot; expiry is enforced on read without any sweep; a lapsed key is reclaimable and replaces the stale row; the sweep deletes only lapsed rows; the sweep is `@SchedulerLock`ed under a unique name |
| `transaction-mgmt/.../idempotency/IdempotencyConfigTest` | Default is the durable store; `memory` is explicit; unknown values (including `redis`) refuse to start; the shipped `application.properties` selects `db`, sets no Redis host, and ships a sweep interval |
| `ops-partner-bff/.../paging/PagingCooldownAcrossReplicasTest` | Two replicas escalating the same alert page **once** — **and two per-JVM cooldowns page twice**; a re-fired alert on another replica is recorded `SUPPRESSED`; a FAILED and a throwing delivery both release the claim so another replica may retry; a delivered page holds the window on both replicas; an unavailable Redis **still pages** and still dedupes locally; an absent reply reads as "page it"; the decision table incl. refuse-to-start; Redis is never wired without the failover decorator |

### 5.2 What the tests do NOT prove — said plainly

**No Redis was ever contacted.** There is no Docker on this machine (every Testcontainers test in
the repo is `@Tag("docker")` and CI-only), so the cross-replica proofs use **two store instances over
one in-process double** with faithful `INCR` / `SET NX EX` / `DEL` / TTL semantics. That is the right
instrument for the property under test — "two instances pointed at one store behave as one" lives in
the store classes' key composition and window arithmetic — and it proves **nothing** about the
Lettuce wire protocol, connection pooling, Lettuce timeout behaviour, or Redis Cluster key routing
(where a multi-key operation would need hash-tag consideration; none of these stores does one). The
alternative given no Docker was an embedded Java Redis dependency, which would have added a
production-classpath-adjacent artifact to prove a property this double already establishes.

transaction-mgmt is the exception: its cross-replica proof runs against a **real database** (H2 in
PostgreSQL mode) with the real Flyway DDL, which is a stronger instrument than the gateway's — one
more incidental benefit of choosing the table.

The paired "and the per-JVM version gets it wrong" assertions are deliberate throughout. Without
them, a green test shows only that the new code runs, not that the defect it was written for existed.

---

## 6. The sweep for other per-JVM state — six more findings, listed and NOT fixed

A systematic sweep of `services/api-gateway`, `services/transaction-mgmt`,
`services/ops-partner-bff` and `libs/**` (`src/main` only) for `static` mutable fields,
singleton-held mutable collections, client-visible sequence generators, and in-memory port
implementations wired as the production default. **None of the below was fixed** — the task asked for
them to be listed. There are **no `static` mutable fields** holding request/money/security/ops state
anywhere in the four directories, and no Caffeine/Guava/`@Cacheable` caching in use.

### 6.1 INCORRECT at N>1

| # | File:line | State | Live in a real deployment? | What breaks at N>1 |
|---|---|---|---|---|
| **1** | `services/ops-partner-bff/.../client/stub/StubOperatorActionAuditClient.java:28-29` | `AtomicLong seq` + `CopyOnWriteArrayList<OperatorActionRecord>` | **YES — this is the live bean in every environment.** `@ConditionalOnProperty(matchIfMissing = true)` and `GMEPAY_OPERATOR_ACTION_AUDIT_CLIENT` is set in **no** values file, no compose service and no properties file | **The strongest finding in the sweep, and the only one that is live today.** Every replica mints `OA-1`, `OA-2`… independently ⇒ **colliding operator-action audit ids**, and the record of *who paused the platform / suspended which partner* is split across replicas and lost on restart. This is a security/ops audit record, not a cache — the failure is an audit trail you cannot reconstruct |
| **2** | `services/ops-partner-bff/.../client/stub/StubOpsControlClient.java:30-35` | `boolean systemPaused`, `maintenanceMode`, three `ArrayList` suspension lists, `String reason` | Default bean, but compose **and** Helm both set `GMEPAY_OPS_CONTROL_CLIENT=rest` (T0-7 wired this) — so incorrect only in an environment that forgets the selector | A **kill switch that applies to one replica**: an operator pauses the platform, the next request lands elsewhere and reads ALL-CLEAR. Money keeps flowing while the console says paused |
| **3** | `services/ops-partner-bff/.../client/stub/StubConfigRegistryClient.java` — stores at `:42,45,54,56,73,1461,1463,1465`; **twelve** `AtomicLong` id minters at `:47,58,75,319,464,675,754,763,920,1467,2061,2263` | `LinkedHashMap` partner/draft/contact/KYB/document stores + 12 client-visible sequences | Default bean; overridden to `rest` in compose and Helm | Twelve id sequences (partner surrogate, contact, KYB, bank account, settlement, prefunding, rule, commission, commercial, document, credential, webhook) restart from the same seed per replica ⇒ **colliding surrogate/document/credential ids**; POSTed drafts and uploaded bytes exist only on the replica that served them |
| **4** | `services/ops-partner-bff/.../client/stub/StubPlatformSettingsClient.java:25` | mutable `LinkedHashMap<String, PlatformSettingView>` | Bare `@Component` with **no `@ConditionalOnProperty` at all** — displaced only because `RestPlatformSettingsClient` is `@Primary`. That is one `@Primary` away from being live | An operator edit to `fx.quote.ttl.seconds`, `prefunding.alert.tier{1,2,3}.pct` or `wallet.fee.krw` lands on one replica ⇒ **non-deterministic reads and silently lost writes on money-affecting settings** |
| **5** | `services/ops-partner-bff/.../client/stub/StubSandboxKeyClient.java:73` | `ConcurrentHashMap<String, List<StoredKey>>` | Default bean; overridden to `rest` | A self-serve SANDBOX key issued via replica A does not exist on B ⇒ the credential the partner was just handed **intermittently fails to authenticate** and intermittently vanishes from the list |
| **6** | `libs/lib-vault/.../InMemoryVaultClient.java:38` (+ the version counter derived at `:73`) | mutable `LinkedHashMap<String, Stored>` | **Registered by `InMemoryVaultAutoConfiguration` under `@ConditionalOnMissingBean`, i.e. the default whenever `gmepay.vault.endpoint` is unset** | KYB document bytes written on A are a 404 from B, and the `v1`/`v2` version counter is computed per-JVM ⇒ **duplicate version numbers for the same (partner, docType)** and dangling `partner_document` rows. The class WARNs about restart loss but says nothing about the multi-replica case |

**The shape of #1–#5 is worth naming as a class**, because it is the same trap the idempotency store
was in (§3.1): a `Stub*` implementation that is `matchIfMissing = true` is production wiring unless
something explicitly overrides it, and whether it is overridden is a property of a values file rather
than of the code. #2/#3/#5 are only safe because T0-7 and T1-3 happened to wire `rest`; #1 is not
overridden anywhere and #4 is not gated at all. **None of these blocks N>1 on the money path** — they
are all on the ops/onboarding surface, which is also why ops-partner-bff's recommended replica count
stays 1 (§4.3) and why this list is a coherent single follow-up rather than six.

### 6.2 HARMLESS at N>1 (cold-cache only) — recorded so the judgement is on the record

| File:line | State | Why harmless |
|---|---|---|
| `services/api-gateway/.../filter/WebClientRbacClaimResolver.java:34` | `ConcurrentHashMap` cache, 60 s TTL | Read-through cache of an RBAC decision: N>1 means N cold caches, not a wrong answer, and staleness stays bounded by the same 60 s as at N=1. Flagged only because it caches a **security** decision — a revocation converges within TTL *per replica* — and because the map is never evicted, so it grows with the principal set |
| `services/api-gateway/.../registry/IpAllowlistCache.java:39` | `ConcurrentMap` cache, 60 s TTL | Same shape for the partner IP allowlist; a revoked CIDR converges within a minute on every replica. Bounded by partners × 2 environments, as its javadoc says |
| `services/ops-partner-bff/.../client/PartnerDirectory.java:73,76` | `resolved` (code→id) + `failedAt` negative cache, 30 s TTL | `resolved` caches an immutable fact (partner codes are frozen post go-live); the negative cache is bounded. Worst case N× the registry warm-up reads |
| gateway/smart-router operating-hours cache (600 000 ms TTL, T3-6) | per-instance | Already documented under T3-6. Adding replicas makes cold-miss stampede marginally more likely, not newly possible |

### 6.3 Reviewed and judged NOT findings

`transaction-mgmt`'s `InMemoryTransactionRepository` (vestigial name; a stateless JPA delegate); all
four transaction-mgmt `@Scheduled` methods (each carries a uniquely-named `@SchedulerLock`, including
the new retention sweeper); `libs/lib-audit`'s `RecordingAuditPublisher` and `libs/lib-events`'
`RecordingEventPublisher` (mutable buffers in `main`, but carry no Spring annotations and are wired as
a bean nowhere in scope — test helpers); api-gateway's `StubConfigRegistryClient` (stateless and
fail-closed since T0-7); Micrometer meters in `lib-errors` (`OutboxLagGauges`, `SchedulerLagProbe`).

**Out of scope by ownership** — `notification-webhook`, `revenue-ledger`, `prefunding`,
`payment-executor` were not swept, and nothing in them is needed for this change.

---

## 7. Things I need elsewhere, or deliberately did not do

1. **`libs/**` was not touched, and no shared Redis client belongs there.** The three stores differ
   in transport (reactive vs servlet), in semantics (`INCR` vs `SET NX` vs a SQL claim) and in
   failure posture (fail-closed vs fail-open) — a shared abstraction would have to be parameterised
   on all three, which is a facade over three different decisions rather than reuse of one. The same
   conclusion the ShedLock follow-up reached for its per-service lock tables.
2. **In files owned by the second agent — nothing needed.** No interface any of them consumes
   changed. Specifically: `notification-webhook`, `revenue-ledger`, `prefunding` and
   `payment-executor` are unaffected by the idempotency store move (the `Idempotency-Key` header
   contract on `POST /v1/transactions` is unchanged, including the 200-vs-201 replay distinction).
3. **`OpsAlertKafkaConsumerConfig` still never calls `setConcurrency`** (T3-11 follow-up 3, in a file
   I own). Not done because it is a Kafka-throughput item, not a replica-correctness one, and it is
   worthless until the topics are repartitioned. One line, still open.
4. **`ops-partner-bff/.../client/rest/ClientBeans.java:39`** — the competing
   `patchCapableRequestFactoryCustomizer` bean (T3-11 follow-up 4) is still there. Untouched: it is a
   timeout concern, and mixing it into a replica-ceiling commit would put two unrelated risks in one
   diff.
5. **The concurrent-duplicate window on `POST /v1/transactions` is real and NOT closed.**
   `TransactionController#create` calls `doCreate(req)` **before** `putIfAbsent`, so two simultaneous
   requests with the same key create **two transactions** and only one response is returned — the
   loser's transaction is orphaned. This is pre-existing, happens on a single JVM today, and is
   **not** what the replica ceiling was about (a sequential retry, the common case, is now correctly
   replayed across replicas). The fix is claim-first: `putIfAbsent(key, RESERVED)` → create →
   overwrite with the real snapshot, answering **409** while a duplicate is in flight and releasing
   the claim if the create throws. That needs two new methods on `IdempotencyStore` (`put`/`remove`)
   and a decision about the loser's status code, so it is a change to the API contract, not a
   storage swap. Recorded here rather than half-built.
6. **Redis itself is a single pod in compose and a single-node default in Helm's on-prem overlay.**
   With the gateway's default fail-closed replay posture, that is a single point of failure for the
   partner edge. All four values files now say so and name the fix per platform, but **no HA Redis is
   configured by this repo** and no `redis` HA chart dependency was added — that is an
   infrastructure decision with a cost, not a values edit.
7. **No HPA and no `replicas: > 1` was set anywhere.** This work makes N>1 *safe*; it does not turn
   it on. `values.yaml` still ships 1 replica per service, which is now a capacity decision rather
   than a correctness constraint — and for ops-partner-bff it must stay 1 for the reason in §4.3.
