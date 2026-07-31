# RUNBOOK — Batch operations (settlement + ZeroPay ZP00xx)

> Operator contract for gap **T3-4**. Companion to `Documentation/RUNBOOK_MONITORING.md` (which covers
> where alerts go) and `Documentation/SETTLEMENT_FLOW_SPEC.md` (which covers what the files mean).

This runbook answers four questions: *did last night's batches run?*, *what do I do when one failed?*,
*how do I re-run one safely?*, and *what do I have to give the platform that it cannot know by itself?*

---

## 0. The one thing an operator must supply: the business-day calendar

**This is a business input, not a configuration detail, and nobody but you can provide it.**

The batch schedulers fire on fixed KST crons. Korean banking holidays are a lunar-calendar set (Seollal,
Chuseok) plus substitute holidays plus temporary public holidays gazetted a few weeks ahead. There is
**no holiday table anywhere in this repository and no rule for deriving one** — deliberately. A hardcoded
table would be wrong for some year and would be trusted anyway, because it would look authoritative.

So the calendar is empty until you populate it:

| Variable | Meaning | Shipped default |
|---|---|---|
| `GMEPAY_CALENDAR_NON_BUSINESS_DATES` | Comma-separated ISO dates the KRW banking system is closed | *(empty)* |
| `GMEPAY_CALENDAR_NON_BUSINESS_DAYS_OF_WEEK` | Recurring weekly closures, e.g. `SATURDAY,SUNDAY` | *(empty)* |
| `GMEPAY_CALENDAR_VERIFIED_THROUGH` | The date through which you assert the list above is complete | *(unset)* |
| `GMEPAY_CALENDAR_FAIL_CLOSED` | `true` = refuse to run on an unverified date instead of running + alerting | `false` |

Set the same values on **both** `settlement-reconciliation` and `scheme-adapter-zeropay` (the property
names are identical so one block of values configures both). In Helm they are per-service `env` entries;
in compose they are plain environment variables.

### What happens while it is empty

Nothing is silently assumed. Every date classifies **`UNVERIFIED`**, and:

* batch windows **still run** (fail-open — a platform that refused to settle until someone typed in a
  holiday table would be worse than one that settles and says so);
* every run row in the ledger is stamped `calendar_verdict = UNVERIFIED`, so you can always prove
  afterwards whether a given file was produced on a day anybody had actually checked;
* the **first run of each business date raises a `BATCH_CALENDAR_UNVERIFIED` WARN ops alert** (once per
  date, not once per window — eight identical daily alerts would train everyone to ignore them).

### Why `verified-through` exists

Without it, a calendar populated for 2026 would keep answering "business day" for every date in 2027 —
"not in my holiday list" silently read as "definitely open". `verified-through` is you asserting the
coverage horizon; past it, dates go back to `UNVERIFIED` and start alerting again. **Extend it every time
you extend the holiday list**, and treat the alert reappearing in January as the reminder that it is due.

### Only turn on `fail-closed` when the calendar is genuinely maintained

`GMEPAY_CALENDAR_FAIL_CLOSED=true` inverts the fail-open behaviour: an `UNVERIFIED` date stops the window
instead of running it. That is the right end state. It also means **every batch stops** the day your
`verified-through` lapses, so do not enable it before someone owns keeping the list current.

---

## 1. Did last night's batches run?

Two ledgers, one per service. Both are append-only: a re-run adds a row, it never rewrites the failed one.

```
GET  /v1/settlements/batch/runs/last-success                     # settlement-reconciliation
GET  /internal/scheme/zeropay/batch/runs/last-success            # scheme-adapter-zeropay  (needs X-Gme-Internal)
```

Each returns the most recent successful run per window, derived from the ledger (so it cannot drift from
it). Compare against the expected schedule:

| KST | Service | Window |
|---|---|---|
| 02:00 / 02:02 | scheme-adapter-zeropay | `ZP0011` payment result / `ZP0021` refund result |
| 05:00 | both | `ZP0061` morning settlement request |
| 10:05 | settlement-reconciliation | `ZP0062` morning result recon (inbound) |
| 14:00 | both | `ZP0063` afternoon settlement request |
| 19:05 | settlement-reconciliation | `ZP0064` afternoon result recon (inbound) |
| 22:00 / 22:02 | both | `ZP0065` payment detail / `ZP0066` refund detail |

For the full history, newest first (bounded; `limit` caps at 500):

```
GET /v1/settlements/batch/runs?outcome=FAILED&limit=50
GET /internal/scheme/zeropay/batch/runs?outcome=FAILED&limit=50
```

### The four outcomes

| `outcome` | Meaning | Action |
|---|---|---|
| `SUCCESS` | Ran, produced (or idempotently re-found) its batch | none |
| `FAILED` | Threw. `failure_class` / `failure_message` / `failure_trace` say why | §2 |
| `SKIPPED_NON_BUSINESS_DAY` | Your calendar declares this date closed | none — the calendar working |
| `SKIPPED_DISABLED` | The feature gate is off | see below |

**`SKIPPED_DISABLED` on a production deployment is itself an incident.** It means
`gmepay.settlement.generation.enabled` / `gmepay.settlement.recon.enabled` /
`adapter.zeropay.batch-enabled` is `false`, so **no settlement files are being produced at all**. These
default to `false` in the image (so dev and CI never auto-generate), which means a deployment that forgot
to flip them looks completely healthy apart from these rows. That is why the disabled case is recorded
rather than only debug-logged.

---

## 2. A batch failed — what now?

You will normally learn this from a **`BATCH_RUN_FAILED` CRITICAL** alert on the `gmepay.ops.alert`
pipeline (the same pipeline and the same sinks as `RECON_BREAK` and `DECLINE_SPIKE` — see
`RUNBOOK_MONITORING.md` for where those land). The alert carries the ledger row id.

1. **Read the row.** `GET .../batch/runs?outcome=FAILED`. `failure_class` distinguishes the common cases:

   | `failure_class` | Meaning |
   |---|---|
   | `BatchPrerequisiteException` | spec §8.2: `ZP0061`/`ZP0063` are blocked until that date's `ZP0011` was transmitted **and** `ZP0012` received. **Fix the ZP0011 run first** — this one is a symptom, not the cause. |
   | `ReconInputMissingException` | ZeroPay's result file is not in the inbox. It owes one for every request it accepted, so on a business day this is an unreconciled settlement. Chase the counterparty / check the SFTP drop. |
   | `NegativeSettlementAmountException` | Refunds exceeded gross for a merchant. Finance decision, not a retry. |
   | anything else | `failure_message` + `failure_trace` (the trace is in the row, deliberately **not** exposed over HTTP). |

2. **Check whether anyone was told.** `alert_status` on the same row is `RAISED`, `FAILED` or
   `NOT_APPLICABLE`. `alert_status=FAILED` with `alert_error` set means *the batch failed **and** the
   notification did not leave the service* — the ledger is then the only record, and your paging config
   needs fixing too.

3. **Note the `calendar_verdict`.** If it is `UNVERIFIED`, confirm the date really was a banking day
   before re-running. Producing a settlement file on a Korean bank holiday is how you get a file KFTC
   rejects or double-counts.

4. **Re-run** — §3.

Because the failed run's transaction rolled back, there is **no** partial settlement batch, no orphan
settlement lines and no half-emitted outbox event to clean up first. The ledger row is the only trace,
which is exactly why it is written in its own transaction.

---

## 3. Re-running a window safely

```
POST /v1/settlements/batch/rerun
{ "fileType": "ZP0061", "businessDate": "2026-07-29",
  "operatorId": "ops-alice", "reason": "05:00 failed: prerequisite not met, ZP0011 since fixed" }

POST /internal/scheme/zeropay/batch/rerun
{ "batchType": "ZP0011", "businessDate": "2026-07-29",
  "operatorId": "ops-alice", "reason": "SFTP timeout" }
```

`businessDate` is **required** — omitting it would silently mean "today", which is not the case you need.
`settlementWindow` is derived from `fileType` and rejected if it contradicts it. `operatorId` and `reason`
are recorded on the ledger row.

### It is safe to invoke twice. Three independent layers make it so:

1. **Refusal.** If a `SUCCESS` row already exists for that `(fileType, window, businessDate)`, the re-run
   is refused with **409 Conflict** naming the run that already succeeded. Regenerating a file the
   counterparty already holds is a money-affecting act, so it must be a deliberate decision, not the
   result of clicking twice.
2. **Domain idempotency.** Even forced, generation is idempotent per `(fileType, businessDate, window)` —
   `createOrGet` plus a PENDING-only guard means an already-`GENERATED` batch is returned unchanged rather
   than producing a second file, second set of lines, or second outbox event.
3. **Ledger append.** Every attempt is recorded, so a double invocation shows up as two rows instead of
   being indistinguishable from one.

### `force: true`

Only overrides layer 1. Use it when you genuinely need to regenerate a window that previously succeeded —
and expect layer 2 to return the existing batch unless it is still `PENDING`. If you need to truly
*replace* a transmitted file, that is a settlement correction, not a re-run: talk to finance and the
counterparty first.

### Re-runs are not a privileged bypass

An operator re-run goes through the same path a cron does: same calendar gate, same ledger row, same
alerting. The only differences are `trigger_source = OPERATOR_RERUN`, the recorded operator/reason, and
that the ZeroPay feature gate is not applied (refusing a hand re-run because a cron is switched off would
leave no way to recover a window manually).

Inbound result-file reconciliation has its own, older endpoint —
`POST /v1/settlements/recon/rerun` with `{ batchId | settlementDate }`. Use that to re-diff a
`ZP0062`/`ZP0064` against a persisted batch; use `/batch/rerun` to regenerate an outbound file.

---

## 4. Authentication

* ZeroPay's endpoints live under `/internal/**`, already covered by that service's internal-auth gate:
  they require the `X-Gme-Internal` shared secret and are fail-closed on a blank one.
* **settlement-reconciliation currently runs no internal-auth filter of its own** — it is an outbound-only
  client of the gated services. Its `/v1/settlements/batch/**` endpoints therefore inherit only whatever
  the deployment puts in front of the service (in-cluster reachability; they are not routed through the
  ingress). Turning its own gate on would make `GMEPAY_INTERNAL_AUTH_SECRET` mandatory for the service to
  boot, which is a deployment change — it is recorded as a follow-up rather than smuggled in here.

---

## 5. What this does NOT do

* **No missed-run detection.** Nothing actively notices that 05:00 produced no row at all; you have to
  look, or build an external alert on `last-success` age. A cron that never fired (dead scheduler, pod
  not running) leaves no row *by definition* — the ledger proves what ran, not what should have.
* **No automatic retry.** A failed window is not retried; it waits for the next scheduled window or an
  operator re-run. Deliberate — most failures here are prerequisite or counterparty problems that a blind
  retry would just repeat.
* **No distributed lock.** Generation is idempotent, so a duplicate fire is a safe no-op, but two
  instances would both do the work. ShedLock remains a documented follow-up for multi-instance.
* **No holiday data.** §0. This is the operator input the platform cannot invent.
* **No transmission.** The detail files (`ZP0065`/`ZP0066`) are generated and tie out, but transmission to
  a live ZeroPay endpoint is still gated on the outstanding IDD items (`van_fee`, `txn_time`, final field
  widths).
