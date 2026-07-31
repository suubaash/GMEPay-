> 작업: N>1 sweep + operational leftovers / 출처: agent

# Every per-JVM holder has a verdict, every stub has an owner, and the chart can finally scale

**Branch:** `feat/exec-gap-closure-2026-07-28` · **Touched:** `services/ops-partner-bff`,
`libs/lib-events-kafka` (test only), `libs/lib-vault` (comments only),
`deploy/helm/gmepay/{templates/hpa.yaml,templates/_deployment.tpl,templates/_helpers.tpl,values.yaml}`,
`.smoke/infra-up.sh`, `scripts/check_helm_chart_wiring.py`, `Documentation/GAP_REGISTER.md`, two
CHANGELOGs, this report. **`docker-compose.yml` was NOT touched** — see §7.

No Docker, no Helm, no server, no fleet was started. No second replica has ever actually existed.

---

## 0. The two tables the task asked for, first

### 0.1 All six per-JVM holders from the replica-ceiling sweep

| # | Holder | Live in a real deployment? | Verdict | What was done |
|---|---|---|---|---|
| **1** | `StubOperatorActionAuditClient` (`AtomicLong` + `CopyOnWriteArrayList`) | Was **YES**, in every environment | **CLOSED** (2026-07-30, prior agent) | Default is now the durable `operator_action_audit` table. Re-verified: the stub is `havingValue = "stub"` with no `matchIfMissing`, and the shipped default is `db` |
| **2** | `StubOpsControlClient` (kill-switch booleans + 3 suspension lists) | No — compose + Helm set `rest`; live only in an environment that forgot the selector | **NOT SHARED, DELIBERATELY — and no longer reachable by accident** | Default inverted to `rest`; stub gated + opt-in; unknown value refuses to boot; javadoc states the N>1 failure (pause on A, ALL-CLEAR on B, money keeps flowing) |
| **3** | `StubConfigRegistryClient` (24 mutable maps + **12** client-visible id minters) | Same | **NOT SHARED, DELIBERATELY** | Same treatment. Javadoc names the worse half: twelve sequences restarting from the same seed on every replica ⇒ **colliding** surrogate/document/credential ids that look perfectly ordinary |
| **4** | `StubPlatformSettingsClient` (mutable settings map) | No — but it had **no `@ConditionalOnProperty` at all**, so it was *constructed* everywhere and displaced only by a `@Primary` | **NOT SHARED — and the structural hazard is gone** | Now gated `havingValue = "stub"`, so it is not created at all by default. It was one removed annotation away from serving `wallet.fee.krw` and the prefunding alert tiers out of a heap map |
| **5** | `StubSandboxKeyClient` (`ConcurrentHashMap` of issued keys) | No — overridden to `rest` | **NOT SHARED, DELIBERATELY** | Same treatment. Javadoc: a just-issued SANDBOX key authenticates intermittently and vanishes from the list intermittently at N>1 |
| **6** | `libs/lib-vault` `InMemoryVaultClient` (map + derived version counter) | **No** — `GMEPAY_VAULT_ENDPOINT` is set in compose **and all four** values files, so `MinioVaultClient` owns the port everywhere | **HARMLESS where it is reachable; now says why it would not be otherwise** | Startup WARN extended from "lost on restart" to the multi-replica half: cross-pod 404s **and two different `v1`s for one `(partnerCode, docType)`**. Class javadoc carries the verdict |

**Why 2–5 are not moved to shared state, stated once because it is one argument.** Each of them
imitates a system that already *is* shared, durable and id-minting: config-registry's `/v1/ops`
gate, its partner registry, its platform-settings table, auth-identity's `api_keys`. Giving the
imitation a Redis or a table means building that system a second time and shipping the weaker copy —
the same reasoning the previous agent used to refuse a Redis `OpsAlertStore`, applied to four beans
whose real versions already exist. What was genuinely wrong was not the state but the **reachability**:
a forgotten selector made them live. That is fixed, structurally, in §2.

**One finding that is NOT in the six, found while verifying #6, and NOT fixed:**
`MinioVaultClient` — the **production** client — derives its version the same way the in-memory one
does: count the objects under the `(partnerCode, docType)` prefix, add one. That is a
read-modify-write against the bucket with no compare-and-set, so two concurrent uploads of the same
document type can both read the same count and both write `vN`, from one pod or several. Fixing it
needs a monotonic source (the `partner_document` row's own sequence, or a conditional put), which is
a change to the document model, not a comment. Recorded in the class and in the register.

### 0.2 Every remaining stub selector in `ops-partner-bff`

Twenty `Stub*` beans. The question for each was T1-1's: **does a real implementation exist, and does
its endpoint exist?** — then invert, or make the fabrication impossible to mistake.

| Selector | Stub(s) | Real client + endpoint verified | Set in compose/Helm before? | Verdict |
|---|---|---|---|---|
| `gmepay.reporting-compliance.client` | `StubReportingClient` | `RestReportingClient` → reporting-compliance `GET /v1/reports` (`ReportController`) — **exists** | **NO — nowhere** | **INVERTED.** Was live in production: the Reports page served fixtures instead of BOK FX1014/FX1015 rows |
| `gmepay.system-health.client` | `StubSystemHealthClient` | `RestSystemHealthClient` → `/actuator/health` on 17 services — **exists** | **NO — nowhere** | **INVERTED.** Was live in production: an operator surface that reported every service **UP** whether or not any were running — structurally unable to report an outage |
| `gmepay.webhook-ops.client` | `StubWebhookOpsClient` | `RestWebhookOpsClient` → notification-webhook `/v1/webhooks/deliveries/{backlog,{id}/replay}` + `/v1/webhooks/endpoints/{signing-health,{id}/rotate-secret}` (`WebhookReplayController`, `WebhookEndpointController`) — **exists** | **NO — nowhere** | **INVERTED.** Was live in production: the T5-8 webhook-secret panel reported zero endpoints, which is exactly why "every deploy target must set `rest`" was never actioned — nothing failed when it wasn't |
| `gmepay.ops-control.client` | `StubOpsControlClient` | `RestOpsControlClient` → config-registry `/v1/ops/**` | yes (`rest`) | **INVERTED** (was safe only by values-file coincidence) |
| `gmepay.config-registry.client` | `StubConfigRegistryClient`, `StubAuditTrailClient`, **`StubPlatformSettingsClient`** | `RestConfigRegistryClient` / `RestAuditTrailClient` / `RestPlatformSettingsClient` | yes | **INVERTED**; the third was a bare `@Component` and is now gated |
| `gmepay.auth-identity.client` | `StubApprovalQueueClient`, `StubRbacAdminClient`, `StubSandboxKeyClient`, **`StubApiKeyClient`** | four `Rest*` siblings | yes | **INVERTED**; the fourth was bare and is now gated |
| `gmepay.transaction-mgmt.client` | `StubTransactionMgmtClient`, **`StubStatementClient`** | `RestTransactionMgmtClient`, `RestStatementClient` | yes | **INVERTED**; the second was bare and is now gated |
| `gmepay.notification-webhook.client` | **`StubPortalWebhookClient`** | `RestPortalWebhookClient` | yes | **INVERTED**; was bare, now gated |
| `gmepay.prefunding.client` | **`StubPrefundingClient`** | `RestPrefundingClient` | yes | **INVERTED**; was bare, now gated |
| `gmepay.revenue-ledger.client` | `StubRevenueLedgerClient` | `RestRevenueLedgerClient` | yes | **INVERTED** |
| `gmepay.settlement-reconciliation.client` | `StubSettlementClient` | `RestSettlementClient` | yes | **INVERTED** |
| `gmepay.operator-action-audit.client` | `StubOperatorActionAuditClient` | `RestOperatorActionAuditClient` → **`POST /v1/audit/operator-actions` exists in NO service** | yes (`db`) | **LEFT AT `db`** — and a test now *asserts* this client is **not** `matchIfMissing`, so a future "make everything rest" sweep cannot fail-close every audited operator action |
| *(none — no selector, no `Rest*`)* | **`StubAuditClient`** | none | n/a | **CANNOT BE INVERTED. Made structurally unmistakable.** It fabricates 25 audit rows for `GET /v1/admin/audit` from 5 action names × 4 actors × a hardcoded "now". Javadoc now leads with "NOT AN AUDIT TRAIL — and there is no selector that makes it one", names the two real surfaces, and the startup banner reports it as unswitchable |
| *(none — no selector, no `Rest*`)* | **`StubRatesClient`** | none | n/a | **CANNOT BE INVERTED. Made structurally unmistakable.** Right arithmetic, invented inputs: hardcoded treasury rates and a flat 1%/1% margin instead of the partner's configured ones, i.e. **plausible and wrong**, which is worse than an error for anyone quoting from it. Same javadoc + banner treatment |

**No client was fabricated.** Twelve selectors were inverted onto clients that already existed and
whose endpoints were grep-verified first. Two stubs had no real implementation and were left as the
only implementation, saying so.

---

## 1. Item 3 — the last Kafka factory, and why deleting the allowlist entry was the fix

`OpsAlertKafkaConsumerConfig` now takes `@Value("${spring.kafka.listener.concurrency:3}")` and calls
`factory.setConcurrency(Math.max(1, concurrency))`. Deliberately byte-for-byte the shape the other
three got: four factories with one defect should be fixed by one shape, or the next reader has to
work out which of four variants is right.

Default **3** is not a tuning guess — it is `KAFKA_NUM_PARTITIONS` in compose and
`SPRING_KAFKA_LISTENER_CONCURRENCY` in the Helm ABI, and `OpsAlertKafkaConcurrencyTest` asserts the
equality so the two cannot drift. The clamp is not defensive noise: `0` from a config typo would mean
**no consumer threads**, i.e. ops alerts silently stop being stored and stop being paged on — silence
on the one pipeline whose entire purpose is that alerts are not silently dropped.

**`KNOWN_UNFIXED` in `KafkaListenerConcurrencyWiringGuardTest` is now `Set.of()`, and that deletion
is part of the fix rather than a consequence of it.** A guard that keeps an allowlist for the one
file it exists to protect is a guard that excuses the defect instead of closing it. The baseline's
"can only ever shrink" property is what made the deletion mandatory: the guard fails when a listed
file has been fixed without its entry being removed, so the two could not be separated even by
accident. The second guard test now requires **four** service-level concurrency tests, not three.

Ordering is unaffected: the producer keys by subject, so every alert for one subject stays on one
partition and is handled in order by one thread; concurrency reorders across subjects only, and the
paging cooldown is claimed atomically per `(alertType|subjectRef)` regardless.

**Unchanged and still operational, not a config change:** existing topics still have one partition,
so concurrency 3 remains an upper bound nothing reaches until they are repartitioned (drop
`kafka-data` locally; set `num.partitions` on MSK *before* the `gmepay.*` topics auto-create; Azure
Event Hubs Standard cannot be raised in place).

---

## 2. Item 2 — the T1-1 defect class, closed as a class

### 2.1 The mechanism was not what the earlier reports assumed

The sweep described the trap as `matchIfMissing = true`. In this service that annotation was
**already dead code**: `application.properties` shipped
`gmepay.<x>.client=${GMEPAY_<X>_CLIENT:stub}`, so the property was *always present* and
`matchIfMissing` never fired. **The real default was the `:stub` in the properties file**, which is
why the three unset selectors were live despite everyone believing the values files governed it.

So the inversion had to be three things at once, not one:

1. `:stub` → `:rest` in the shipped properties (the default that actually decided);
2. `matchIfMissing = true` moved from the 12 stubs onto the 17 `Rest*` beans (so the annotation says
   the same thing the properties file does, and a deployment that *removes* the property still gets
   the real client);
3. the five bare `@Component` stubs gated `havingValue = "stub"` (they were constructed in every
   environment and displaced only at injection time by a `@Primary`).

An unrecognised value now leaves **no bean**, so the controller that requires the port fails context
refresh and the service refuses to start. That is the same refuse-to-boot idiom as T0-7's
`gateway.partner-credentials.source` and the operator-audit selector, and it is the right answer here
for the same reason: a typo must not resolve to something that looks functional.

### 2.2 What it costs, said plainly

The BFF no longer boots into a fully-faked world by accident — which is the point — but the
convenience it used to give away for free is now an explicit act: `GMEPAY_<UPSTREAM>_CLIENT=stub`,
per upstream. `BffSecurityFilterChainTest` is the first caller to pay that cost and does so in its
own annotation, with the reason written there; without it, its 200-path assertions would have opened
real sockets to `config-registry:8080`. That test failing was the change working.

**No `docker-compose.yml` or values-file edit was needed anywhere**, because the eight selectors those
files pin already say `rest` and the inversion covers the ones they never mentioned. That also means
this change adds nothing to the other agent's files.

### 2.3 The banner, and why one instead of eighteen

`StubClientSelectionWarner` logs a single WARN at `ApplicationReadyEvent` naming every stub the
container actually wired, each with the selector that would replace it — and INFO ("every upstream
client is live") when none is, because the absence of a warning has to be a positive statement rather
than silence that could equally mean the check never ran.

Per-class constructor WARNs were the obvious alternative and are worse in the way that matters: they
are easy to *omit* when a new stub is added, which is the same "someone must remember" failure that
produced the defect. Enumerating the container cannot forget a bean. It is also the only mechanism
that can report `StubAuditClient` and `StubRatesClient`, which have no selector to log about.

### 2.4 The guard

`StubClientSelectorInversionTest` fails the build on: a new `:stub` default in the shipped
properties; any `matchIfMissing` in the stub package (comments stripped first, so the classes may
*document* that they used to carry it); an ungated stub that has a `Rest*` counterpart; a `Rest*`
selector bean that does not win when its property is absent; `RestOperatorActionAuditClient`
accidentally *gaining* `matchIfMissing`; a stub missing from the banner's selector map; and the three
full decision tables (absent → live, `rest` → live, `stub` → stub, four bogus values → no bean **and
a consumer that cannot start**).

---

## 3. Item 4 — `.smoke/infra-up.sh`

`--partitions 1` → `--partitions 3`, with the reason in the file: Kafka assigns **whole** partitions,
so a one-partition topic caps every consumer group at one working thread whatever
`spring.kafka.listener.concurrency` or the replica count says. Bootstrapping through that script
silently re-imposed the ceiling the Kafka work had just removed. Replication stays 1 and now says
so — a single-broker dev stack stated rather than defaulted into, matching
`KAFKA_DEFAULT_REPLICATION_FACTOR` in compose.

**One thing nobody noticed while filing this as a follow-up three reports running:
`.smoke/` is gitignored** (`.gitignore:15`). The file is untracked, so the edit exists in this
working tree and **is not in the commit and does not propagate to anyone else** — `git add` on it is
refused. Two honest options for the owner, neither of which I took unilaterally because both change
what the repo treats as source: un-ignore `.smoke/` (it currently holds local scratch as well), or
move the bootstrap script somewhere tracked (`scripts/`) and delete the ignored copy. Recorded here
and in the register rather than left as a fix that silently only exists on one machine.

---

## 4. Item 5 — the chart can scale, and still ships one replica everywhere

### 4.1 What was added

| Piece | Detail |
|---|---|
| `templates/hpa.yaml` | One `autoscaling/v2` HPA per opted-in service. Per-service `autoscaling.{enabled,minReplicas,maxReplicas,targetCPUUtilizationPercentage,targetMemoryUtilizationPercentage,metrics,behavior}` |
| `_helpers.tpl` → `gmepay.autoscalingEnabled` | Resolves per-service over fleet-wide **explicitly** via `hasKey`, not `default` — `default` treats `false` as empty, so a per-service `enabled: false` would silently fall through to a `true` fleet switch |
| `_deployment.tpl` | **Omits `spec.replicas`** when an HPA manages the Deployment. Both templates read the same helper, so they cannot disagree |
| `values.yaml` | The `autoscaling:` master switch (`false`) + the per-service safety list + `services.ops-partner-bff.replicas: 1` pinned explicitly |
| `check_helm_chart_wiring.py` §8b | Fails if the template disappears, if the fail guards go, if the Deployment stops yielding `replicas`, if any values file enables autoscaling, or if any service ships >1 replica |

Omitting `replicas` under an HPA is not tidiness: a Deployment that keeps a hardcoded count while an
HPA scales it fights the HPA on every `helm upgrade`, and the visible symptom is an autoscaler that
"randomly" resets the pod count mid-load.

### 4.2 No number was invented, and that is enforced

Enabling autoscaling **without `maxReplicas`, or without any metric, fails the template** — same
idiom as `ingress-ipn.yaml`'s empty-`sourceRanges` guard. A plausible-looking `maxReplicas: 5` /
`targetCPUUtilizationPercentage: 70` would be read as an engineering position on cost and traffic
that this repository has no basis for. You cannot get a ceiling by forgetting one; only by choosing
it. A `maxReplicas` below `minReplicas` also fails.

### 4.3 Which services are safe to scale — and which are not

**Safe at N>1 (correctness; still a cost decision):**

- **api-gateway** — but only with Redis. `SPRING_DATA_REDIS_HOST` is in the ABI ConfigMap and
  `gateway.shared-state.store=auto` resolves to it; with `store=memory` the service is capped at 1
  again and logs that it is.
- **transaction-mgmt** — idempotency claims + `ux_transactions_partner_txn_ref` live in its own DB.
- **payment-executor, settlement-reconciliation, notification-webhook, scheme-adapter-zeropay,
  revenue-ledger, prefunding** — every `@Scheduled` job holds a uniquely-named ShedLock lease and the
  pools are sized.
- **ops-partner-bff** — **safe only because its ops-alert store, alert ids and operator acks moved
  into its own `bff` database, and that database is not provisioned in any real environment yet.**
  The AWS/Azure overlays point at placeholder managed instances. Until it exists, a chart upgrade
  fails at Flyway (loudly, which is right) and the service must not be scaled. That is why its `1` is
  pinned in the values file with the reason, rather than inherited from `global.defaultReplicas`.

**NOT assessed — the absence of a warning is not approval:** `auth-identity`, `config-registry`,
`rate-fx`, `smart-router`, `qr-service`, `merchant-qr-data`, `reporting-compliance`, `kyb-adapter`,
`scheme-adapter-{sendmn,ninepay,nepal}`, `admin-ui`, `partner-portal-ui`. These were never swept for
per-JVM request/money state. `reporting-compliance` additionally writes generated filing files to an
`emptyDir`, so a second replica holds a different set of files.

**Kafka caveat, which is not about state:** a topic still created with one partition gains nothing
from a second replica *or* from `SPRING_KAFKA_LISTENER_CONCURRENCY`.

All of this is in `values.yaml` where an operator will read it, not only here.

---

## 5. Verification

| Check | Result |
|---|---|
| `:services:ops-partner-bff:test` | **540** tests, 0 failures, 0 errors, 0 skipped (was 529) |
| `:libs:lib-events-kafka:test` | **17** tests, 0 failures, 0 errors |
| `cmd //c gradlew.bat testClasses` (whole repo) | **BUILD SUCCESSFUL** |
| `check_helm_chart_wiring.py` | **299/299** (includes the new §8b) |
| `check_internal_auth_wiring.py` | 121/121 |
| `check_monitoring_wiring.py` | 37/37 |
| `check_gitleaks_config.py` | findings=0, missed=0, false-positives=0, re2-bad=0 |
| `check_load_harness_wiring.py` | OK |
| `node docker/keycloak/check-topology.mjs` | 101/101 |
| PyYAML parse | `docker-compose.yml` + all four Helm values parse as dicts; asserted programmatically that `autoscaling.enabled` is `false`, `global.defaultReplicas` is 1, **no** service enables autoscaling, `ops-partner-bff` is pinned to 1, and `KAFKA_NUM_PARTITIONS`/`SPRING_KAFKA_LISTENER_CONCURRENCY` are both still 3 |
| Helm templates | **Static only, no Helm binary run** (§5.2) — Go-template action balance verified for `hpa.yaml`, `_deployment.tpl`, `_helpers.tpl`, `deployments.yaml`, plus the guard's structural assertions |

Failures seen and fixed along the way, because they are the evidence the change does something:
`BffSecurityFilterChainTest`'s three 200-path assertions started opening real sockets to
`config-registry:8080` the moment the defaults inverted, and the first version of the
`matchIfMissing` scan failed on four files that merely *document* the old annotation.

### 5.1 New tests, and what each proves

| File | What it proves |
|---|---|
| `ops-partner-bff/.../alert/OpsAlertKafkaConcurrencyTest` | The concurrency is read **back off the built factory** (the only way to distinguish "unset" from "unreadable"); the default is 3, equal to the partition count; `0` clamps to 1 |
| `ops-partner-bff/.../client/StubClientSelectorInversionTest` | No shipped `:stub` default survives; no stub carries `matchIfMissing` (comments stripped, so the history may stay in the javadoc); every stub with a real counterpart is gated; every `Rest*` selector bean wins when the property is absent **and `RestOperatorActionAuditClient` deliberately does not**; three full decision tables including four bogus values leaving no bean and a consumer that cannot start; the banner reports exactly what is wired and nothing when nothing is; every gated stub is in the banner's selector map |
| `libs/lib-events-kafka/.../KafkaListenerConcurrencyWiringGuardTest` (changed) | `KNOWN_UNFIXED` is empty; four service-level concurrency tests are required |

### 5.2 What was NOT verified — said plainly

- **No Helm, no Docker, no server, no second replica.** `hpa.yaml` has never been rendered by Helm.
  The claim "it renders nothing by default" is a static argument (`autoscaling.enabled: false`, no
  service key sets it, so the `range` body is skipped), backed by an action-balance check and the
  guard's assertions — **not** by a `helm template` run. A syntax error Go's parser would catch and my
  balance check would not is possible; the first `helm template` in CI is the real test.
- **The three inverted clients have never contacted their real upstreams here.** Their endpoints were
  verified to *exist* by grep against the controllers, which is T1-1's first step and is what makes
  the inversion safe rather than optimistic — it is not the same as an integration run. In particular
  `RestSystemHealthClient` fans out to 17 services with a 3 s per-probe ceiling, and nobody in this
  repository has watched it do that against a real fleet.
- **`.smoke/infra-up.sh` was not executed** (it needs WSL + Docker). The change is one flag.

---

## 6. Not done, deliberately, with the reason

1. **`MinioVaultClient`'s version derivation is a read-modify-write with no compare-and-set** (§0.1).
   A real multi-writer defect in the production client. Needs a monotonic source, i.e. a change to the
   document model — not a comment, and not something to guess at inside a sweep.
2. **`StubAuditClient` still backs `GET /v1/admin/audit`.** Retiring that page onto config-registry's
   hash-chained `audit_log` (via the `AuditTrailClient` that already exists) — or deleting it — changes
   an Admin UI route's contract, which is a product decision. It is now impossible to mistake for real,
   which is the half that belongs in this change.
3. **`StubRatesClient` has no `rate-fx` adapter.** Writing one is a new integration, not a wiring fix.
4. **`ops-partner-bff/.../client/rest/ClientBeans.java:39`** — the competing
   `patchCapableRequestFactoryCustomizer` bean (T3-11 follow-up 4) is still there. Third report in a row
   to say so; it is a timeout concern and mixing it in here would put two unrelated risks in one diff.
5. **Kafka topic repartitioning** remains an operational migration (§1).
6. **The `bff` database does not exist in AWS/Azure.** Provisioning step, not a values edit — and it is
   what gates ops-partner-bff actually being scaled.
7. **No service was set above 1 replica and no HPA was enabled.** That is the task's own instruction and
   the right one: it is a cost decision.

---

## 7. Concurrency with the other agent

`docker-compose.yml` and `deploy/helm/gmepay/values-{aws,azure,onprem}.yaml` were **not touched** —
the inversion needed no new env key anywhere, which is one of its better properties.
`deploy/helm/gmepay/values.yaml`, `Documentation/GAP_REGISTER.md`,
`scripts/check_helm_chart_wiring.py` and `libs/lib-events-kafka/...GuardTest` all had concurrent
edits from the other agent in the same working tree; each was re-read immediately before editing,
every change is additive, and hunk-level staging (`git apply --cached` of a filtered patch) was used
wherever a file carried foreign hunks — `scripts/check_helm_chart_wiring.py` still has that agent's
uncommitted section 8 in the working tree and none of it is in my commit.

**Two exceptions, stated rather than glossed:**

- The other agent **committed my `GAP_REGISTER.md` additions inside their own commit** (`976b166`)
  before I got to them. The text is intact and in history; it is just attributed to their commit.
  The later `.smoke`/gitignore correction is in mine.
- The reverse happened once, and I chose it deliberately. Their new
  `defaultConcurrencyMatchesTheBrokersPartitionCount` test sits **inside** the same diff hunk as my
  `KNOWN_UNFIXED` change in `KafkaListenerConcurrencyWiringGuardTest`, so a hunk-level split would
  have committed their test body **without its two `java.util.regex` imports** — a commit that does
  not compile. I staged the whole file instead. Their test is green
  (`:libs:lib-events-kafka:test --rerun-tasks`, 6 tasks executed, BUILD SUCCESSFUL) and it is a
  strict improvement to the same guard, but it is theirs, not mine.

One thing worth flagging: `check_gitleaks_config.py` reported **findings=1** mid-session
(`gmepay-test-platform/src/engine/credentials.ts:341`, a variable *reference* rather than a literal).
It was the other agent's in-flight allowlist edit, not mine, and it was green again minutes later
with no action from me. Recorded so the green result above is not read as "it was never red".
