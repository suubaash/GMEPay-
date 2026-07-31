> 작업: T3-4 batch ops + Helm chart audit / 출처: agent

# T3-4 batch operations + the Helm chart audit behind T3-10 / T3-9

Closes (partially) **T3-4**, **T3-10** and **T3-9** in `Documentation/GAP_REGISTER.md`; COO audit finding
**#6** (`outputs/agent/audit_coo-ops_2026-07-28.md` §6). Operator-facing companion:
`Documentation/RUNBOOK_BATCH_OPS.md`. Reuses the alerting pipeline built for T3-3
(`outputs/agent/fix_t3-monitoring-alerting_2026-07-28.md`) rather than adding a second one.

**Constraints honoured.** Only `services/settlement-reconciliation`, `services/scheme-adapter-zeropay`,
`deploy/helm/**`, `scripts/**`, `Documentation/**` were touched. The chart audit found omissions belonging to
`rate-fx`, `qr-service`, `kyb-adapter`, `reporting-compliance`, `scheme-adapter-nepal`,
`scheme-adapter-sendmn` and `scheme-adapter-ninepay` — every one was fixed **in the chart**, and none of
their Java was edited. No server, container, Helm command or Docker daemon was started.

**Session note.** This work was interrupted mid-edit by a session limit and resumed. The first act on resume
was to repair the break left behind (`SettlementBatchJobService`'s two legacy constructors, §1.1), not to
continue forward.

---

## 1. What the audit claimed, and what was actually true

Verified against the code before changing anything.

| Claim | Verified | Detail |
|---|---|---|
| ZeroPay's six crons are log-and-swallow | **Yes** | Six near-identical `catch (Exception e) { log.error(…) }` blocks in `ZeroPayBatchScheduler`. |
| Settlement generation the same | **Yes** | `SettlementGenerationScheduler` lines ~83/94. |
| No ERROR row is persisted | **Yes, and worse than "not persisted"** | `runWindow` is `@Transactional`. A failure doesn't just skip writing a marker — it **rolls back the batch, its `settlement_lines` and the outbox row**, so the failed run leaves *no trace of any kind*. The class javadoc already admitted this ("persisting an ERROR row … is a follow-up"). |
| No rerun tooling | **Yes for outbound, no for inbound** | `ReconRerunController` + `ReConExceptionController` existed for the *inbound* re-diff. Nothing existed for **outbound generation** — the half that produces the file the counterparty acts on. The ZeroPay adapter had **no manual trigger of any kind**. |
| Holidays ignored | **Yes** | Fixed crons, no calendar consulted anywhere. |
| `config-registry` already has a holiday-aware `BusinessDayCalendarEntity` | **True but unusable here** | That service was owned by a concurrent workstream, and reaching across a service boundary for a calendar would have made every batch window depend on config-registry being up. Noted as the eventual consolidation target. |

**Three things the audit did not say, found while working:**

1. **`scheme-adapter-zeropay` had no `EventPublisher` on its classpath at all.** No `lib-events-kafka`, no
   log fallback, no ops-alert path. So "every scheduler catch publishes an `ops.alert`" was not a small
   change there — the transport did not exist. This is the same first-hop break T3-3 found in
   payment-executor.
2. **The business date was hardcoded deep inside the job.** `runWindow` computed
   `LocalDate.now(KST)` internally, so even with a rerun API bolted on, **nothing could re-run yesterday** —
   the single most likely thing an operator needs after a failed 22:00.
3. **A missing inbound file was a `log.warn` and an early return**, i.e. indistinguishable from a clean day.

### 1.1 The resumed break

`SettlementBatchJobService` had gained a `BusinessCalendar` constructor parameter; its two legacy
convenience constructors had not been updated, so the tree did not compile. Both now pass
**`BusinessCalendar.empty()`**, which I confirmed from the landed code rather than from recall: an empty
calendar has no data, so `classify` returns `UNVERIFIED` for every date, and `empty()` sets
`failClosed=false`, so `gate` never throws. A legacy caller therefore asserts **nothing** about business days
(it does *not* claim every day is open) and behaves byte-for-byte as before. Inventing a business-day default
there is exactly what this gap forbids.

Also found on resume: the `calendar` field had been **declared but never used**, while its javadoc claimed
"Consulted HERE". That is the doc-vs-reality drift this branch has spent the day removing, so the gate calls
were added to `runWindow` and `runDetailWindow` for real.

---

## 2. T3-4 — failures made visible, then actionable

### 2.1 Durability: why `REQUIRES_NEW` is the whole fix

Two append-only ledgers — `batch_runs` (settlement, Flyway **V012**) and `zp_batch_runs` (zeropay, Flyway
**V004**). Next free version verified per module; both modules are flat with no vendor subdirectories to
mirror; portable types only, consistent with their existing migrations.

The load-bearing detail is that `BatchRunRecorder` is annotated
`@Transactional(propagation = REQUIRES_NEW)`. A write on the *same* transaction would be rolled back with the
failure it describes; a write that merely happened later, if it joined a still-rollback-marked transaction,
would fail with `UnexpectedRollbackException`. `REQUIRES_NEW` suspends whatever is in flight and commits on
its own connection, so the evidence lands under both call shapes rather than only the one currently used.

**Asserting the annotation exists would prove nothing**, so `BatchRunLedgerDurabilityIT` drives a real
`TransactionTemplate` that writes the row and then throws, against the real Spring context and real JPA/H2,
and reads the row back afterwards.

One row answers three separate operational questions:

| Question | Column |
|---|---|
| Did last night's 05:00 run? | newest row per `(file_type, settlement_window, business_date)`; `GET …/runs/last-success` derives it |
| Why did it fail? | `failure_class` / `failure_message` / `failure_trace` (bounded excerpt) |
| Did anyone find out? | `alert_status` / `alert_error` |

Deliberate choices: **`SKIPPED_DISABLED` is recorded**, because a deployment that forgot
`generation.enabled=true` produces no settlement files at all and looks perfectly healthy otherwise — that is
itself a T3-4-class silent failure, and the ledger is the only place it surfaces. Widths are enforced in the
setters and the recorder **never throws** (it returns `null` and logs), because a recorder that threw inside a
catch block would replace the real diagnosis with a bookkeeping error. The stack excerpt is capped at 4000
chars — enough to identify the throw site, bounded so the ledger is not what fills the disk — and is
**not exposed over HTTP** (it can carry internal paths and SQL).

### 2.2 Alerting: the existing pipeline, not a second one

`BATCH_RUN_FAILED` (CRITICAL) and `BATCH_CALENDAR_UNVERIFIED` (WARN) are emitted as `OpsAlertPayload` on
`gmepay.ops.alert` through the `opsAlertPublisher` seam that already carried `RECON_BREAK` — Kafka when a
broker is configured, log fallback otherwise. So they arrive at whatever sink the operator already pointed at
for T3-3, with **no new URL to configure and no second payload shape to template against**.

Consequently there is deliberately **no `AlertSink` interface in these services**: introducing one here would
be the parallel notification path the brief warned against. What T3-3 built is the transport; this work only
decides *when* and *how loudly*.

For zeropay this required adding `lib-events-kafka` plus a `ZpOpsAlertConfig` mirroring `ReconAlertConfig`.
The auto-config is inert without `spring.kafka.bootstrap-servers`, so local/CI boots stay broker-free, and
because this adapter publishes no domain events of its own, no *other* event starts being emitted as a
side effect. Its log fallback logs at **WARN**, not INFO: an alert that reached only a log file is itself
worth seeing at default levels.

Alerting **never throws**; a dead sink becomes `alert_status=FAILED` on the row, making *"the run failed and
nobody was told"* a queryable state rather than a silence. Ordering is persist-first-then-notify, matching the
contract `OpsAlertPipeline` established for T3-3.

The calendar warning is **de-duplicated to one per business date**. Eight identical WARNs every day until
someone populates a holiday table is an alert stream nobody reads, which would defeat the purpose; the
per-run evidence is never lost, because every row carries its own `calendar_verdict`.

### 2.3 Re-run tooling — extending `ReconRerunController`, not paralleling it

`POST /v1/settlements/batch/rerun` and `POST /internal/scheme/zeropay/batch/rerun`, same
request/response/service/controller quartet, same `operatorId` + `reason` discipline, same
exception-to-status mapping as the existing recon re-run. `businessDate` is **required** — omitting it would
silently mean "today", which is not the case an operator needs — and `runWindow` / `runDetailWindow` gained
date-parameterised entry points so a named date is genuinely re-runnable (§1, finding 2).

**Safe to invoke twice, via three independent layers:**

1. **Refusal.** A `SUCCESS` row for those coordinates ⇒ **409**, naming the run that already succeeded.
   Refusal rather than a silent no-op on purpose: a silent success reports "done" for two materially
   different situations, and after a file has been transmitted, regenerating it is a money-affecting act that
   must be a deliberate `force=true`.
2. **Domain idempotency.** Even forced, `createOrGet` + the PENDING-only guard return the existing batch
   rather than writing a second file, second set of lines or second outbox event.
3. **Ledger append.** Every attempt is a row, so a double invocation is visible instead of indistinguishable.

Re-runs are **not a privileged bypass**: they go through the same `BatchRunExecutor` as a cron, so same
calendar gate, same ledger row, same alerting. The single divergence is that ZeroPay's `batch-enabled` feature
gate is not applied to an operator re-run — refusing a hand re-run because a cron is switched off would leave
no way to recover a window manually, which is the hole this gap is about.

### 2.4 The calendar — an input, not an invention

**`BusinessCalendar` contains no Korean holiday data and no rule for deriving one.** Korean banking holidays
are lunar (Seollal, Chuseok) plus substitute holidays plus temporarily-gazetted ones; any table hardcoded here
would be wrong for some year and would be trusted anyway because it looked authoritative.
**Populating it is an operator / business input** — stated in `RUNBOOK_BATCH_OPS.md` §0, in both
`application.yml` blocks, and in the chart.

Four settings, identical property names in both services so one block configures both, every default "no
data": `non-business-dates`, `non-business-days-of-week`, `verified-through`, `fail-closed`.

**Three verdicts, not two.** This is the design decision that carries the gap. An empty calendar answers
`UNVERIFIED` — never `BUSINESS_DAY` — because "not in my holiday list" silently read as "definitely open" *is*
the assumption being removed. On `UNVERIFIED` the run **proceeds** (fail-open: a platform that refuses to
settle until somebody types in a holiday table is worse than one that settles and says so), is stamped
`calendar_verdict=UNVERIFIED`, and raises the WARN.

`verified-through` exists so a calendar populated for 2026 stops silently vouching for 2027; past the
horizon dates return to `UNVERIFIED` and start alerting again. **Weekends are configuration too**, with an
empty default — KRW banking is genuinely closed at weekends, but which windows an operator wants suppressed
on a Saturday is a business decision, not a fact to bake in.

Enforcement lives in `SettlementBatchJobService` itself, not only the schedulers, so no caller — scheduler,
operator re-run, or a future direct one — can bypass a closed-day verdict. Because holidays are now skipped
*first*, turning a missing inbound file into a recorded failure (`ReconInputMissingException`) became
affordable: it no longer false-alarms on every Korean bank holiday.

### 2.5 One executor, not eight catch blocks

`BatchRunExecutor` / `ZpBatchRunExecutor` implement calendar-gate → alert-if-unverified → persist → alert →
stamp **once**, so the eight call sites cannot drift apart the way the original six ZeroPay copies had. The
swallow still happens — a failed 05:00 must not kill the scheduler thread or skip 14:00 — it is simply no
longer silent.

---

## 3. Part B — the Helm chart audit (T3-10), and what it found

T3-10 recorded one datasource omission but named the thing that mattered: *"it implies the Helm path has
never been exercised end-to-end."* That was correct. `scripts/check_helm_chart_wiring.py` (**166/166**)
derives each service's requirements **from its own `application.*`** rather than carrying a hardcoded list:
a Dockerfile ⇒ the chart must create the service; an env-resolved `spring.datasource.url` with an in-memory
H2 fallback ⇒ the chart must set a real one; `${VAR}` with no default ⇒ mandatory; `${VAR:default}` pointing
at localhost or a bare in-cluster host ⇒ must be overridden; every `envSecretKeys` entry ⇒ must exist in
`secrets.data`. Comment lines are stripped first — which is why api-gateway's *documented* `${ACME_API_KEY}`
example is correctly **not** treated as a requirement.

| # | Finding | Why it matters |
|---|---|---|
| **a** | **Four more services had no datasource**: `rate-fx`, `qr-service`, `kyb-adapter`, and worst **`reporting-compliance`** | Exactly the T3-10 bug, four more times. reporting-compliance's entire output is a regulatory filing; its KOFIU/BOK aggregates and `reporting_run` rows were being wiped on every restart. |
| **b** | **`scheme-adapter-sendmn` and `scheme-adapter-ninepay` were absent from the chart entirely** | Both deployable, both on live cross-border payout paths (MNT / VND). `helm install` never created them, so any transaction routed to either corridor failed at DNS. Their image defaults are **localhost simulators**, so both now carry explicit `REPLACE-` placeholders. |
| **c** | **All 33 in-chart base URLs were bare hostnames** | Services render as `{{ .Release.Name }}-<key>`, so `http://config-registry:8080` resolves to **nothing** in any real release — i.e. *every* cross-service call in a Helm deployment failed DNS. Fixed by rendering per-service env through `tpl` and writing the URLs release-aware. |
| **d** | **`scheme-adapter-nepal` referenced two Secret keys declared nowhere** | A `secretKeyRef` to an absent key is not a soft failure: the kubelet refuses to start the container. That pod could never have come up. |
| **e** | **`settlement-reconciliation`'s cross-service URLs were unset** | It doesn't use the fleet's `GMEPAY_*_BASE_URL` convention (it reads `TRANSACTION_MGMT_URL`, `PREFUNDING_URL`, …). Its fallbacks were wrong *twice over*: bare hostnames **and** per-service ports (`transaction-mgmt:8082`, `revenue-ledger:8084`) that the chart normalises to 8080. Its `*_ENABLED` gates also ship default-`false`, so the spec §8.2 prerequisite and the rounding-residual post were silently off in Helm. |

Finding **(c)** is the one that most confirms the register's suspicion: a chart in which no service can reach
any other has never been rendered against a cluster.

### 3.1 T3-9 — documented, not built

No StatefulSet or PVC was added, deliberately: in every target environment these are managed services, and a
chart shipping single-replica StatefulSets would invite someone to run the money path's database on an
emptyDir-grade volume. What changed is that the assumption is now **explicit where an operator will read
it** — a `WHAT THIS CHART DOES NOT DEPLOY` block at the top of `values.yaml` naming all six external
dependencies and stating that the chart carries no backup story, that `scripts/backup/` is `docker exec`-based
and **does not apply** to Kubernetes, and that `helm install` succeeding proves nothing about whether the
datastores are reachable, sized or backed up. The guard asserts the posture stays true *and stays documented*.

---

## 4. Files

**Created** — `Documentation/RUNBOOK_BATCH_OPS.md` · `scripts/check_helm_chart_wiring.py` ·
settlement `calendar/{BusinessCalendar,BusinessDayVerdict,NonBusinessDayException}.java` ·
settlement `runlog/{BatchRunEntity,Repository,Recorder,Alerter,Executor,Outcome,Trigger}.java` ·
settlement `rerun/{BatchRerunController,Service,Request,Response,AlreadySucceededException}.java` ·
settlement `scheduler/ReconInputMissingException.java` · `V012__create_batch_runs.sql` ·
zeropay `ops/{ZpBatchRun*,ZpOpsAlert*,ZpLoggingEventPublisher}.java` + `ops/calendar/*` ·
zeropay `api/ZeroPayBatchRerunController.java` · `V004__create_zp_batch_runs.sql` ·
tests `BatchRunLedgerDurabilityIT`, `BatchRerunServiceTest`.

**Modified** — settlement `SettlementBatchJobService` (calendar + date-parameterised windows),
`SettlementGenerationScheduler`, `ReconScheduler`, `application.yml` · zeropay `ZeroPayBatchScheduler`,
`build.gradle`, `application.yml` · `deploy/helm/gmepay/{values.yaml,values-aws,values-azure,values-onprem,templates/_deployment.tpl}` ·
`SettlementGenerationSchedulerTest`, `BatchPrerequisiteGateTest` · `Documentation/GAP_REGISTER.md`.

---

## 5. Verification

```
gradlew :services:settlement-reconciliation:test :services:scheme-adapter-zeropay:test  → BUILD SUCCESSFUL
python scripts/check_helm_chart_wiring.py        → 166/166   (new)
python scripts/check_internal_auth_wiring.py     → 94/94
python scripts/check_monitoring_wiring.py        → 37/37
python scripts/check_gitleaks_config.py          → findings=0 missed=0 false-positives=0
node   docker/keycloak/check-topology.mjs        → 101/101
PyYAML parse: docker-compose.yml (45 svcs) + all 4 Helm values (22/10/10/0) → OK
```

**`gradlew testClasses` (repo-wide) is RED, and not from this work.** With `--continue` the *only* failure is
`services/auth-identity/.../RbacAdminServiceAuditTest.java:272` — `PrincipalEntity.Type.HUB_USER` cannot be
resolved — which belongs to the concurrently-running audit-trail workstream. Both of my modules'
`compileJava`, `compileTestJava` and `test` are green.

**A real bug the tests caught.** The first Spring-context run of the zeropay suite failed 23 `InternalAuthGateTest`
cases with *"No default constructor found"* for `BusinessCalendar`. Cause: it has two constructors (`@Value`
prod + explicit test) and a `@Component` with more than one gives Spring nothing to choose with. This is the
repo's already-documented two-constructor gotcha; `@Autowired` is now on the prod constructor in **both**
copies, and the guard asserts it stays there. Had the zeropay context not been exercised, this would have
shipped as a boot failure.

---

## 6. Open / follow-ups

1. **No missed-run detection.** A cron that never fired leaves no row *by definition*. The ledger proves what
   ran, not what should have. Needs an external alert on `last-success` age.
2. **The calendar is still empty**, and `fail-closed` stays `false`. Someone must own keeping it current
   (and extending `verified-through`) before fail-closed can be turned on — otherwise every batch stops the
   day it lapses.
3. **Nothing was rendered by `helm template` or applied to a cluster.** The guard is static. The honest
   reading of T3-10 is that the chart is now verified *by derivation*, not by a deploy.
4. **`BusinessCalendar` is duplicated** across the two services because `libs/` was concurrently owned.
   Promote to `libs/lib-errors` (which already hosts the shared platform seams); the guard asserts the two
   copies stay logically identical until then. `config-registry`'s `BusinessDayCalendarEntity` is the eventual
   consolidation target.
5. **settlement-reconciliation runs no internal-auth filter of its own**, so `/v1/settlements/batch/**`
   inherits only in-cluster reachability. Turning its gate on makes `GMEPAY_INTERNAL_AUTH_SECRET`
   boot-mandatory — a deployment change, not a batch-ops change.
6. **No automatic retry** and **no ShedLock** (both deliberate; see the runbook §5).
7. **The 9Pay manual-email escalation path still has no owner** — the other half of the T3-4 register line,
   untouched here because it is a process/ownership question, not code.
8. `OpsAlertPayload`'s javadoc lists the known `alertType` values and does not yet mention the two added
   here; `libs/lib-api-contracts` was out of scope.
