> 작업: T2-5 ledger ops / 출처: agent

# T2-5 — ledger-posting replay, day-close, FX exposure, scheduling

**Scope touched:** `services/payment-executor` **only**. `services/revenue-ledger` needed **no change** — T2-4 had
already built both reports this work consumes, and the right move was to consume them rather than grow a second copy
of the ledger's arithmetic outside the ledger.

**Result:** 400 tests green in `payment-executor` (67 new across 8 new classes), 120 green in `revenue-ledger`,
`gradlew testClasses` green fleet-wide.

**Commit note:** my files were swept into commit `0885f94` by a concurrently-running agent that staged with
`git add -A` before I had committed. I verified every file arrived intact and unmodified; only the register entry and
this document are committed by me. The T2-5 register entry that agent wrote was thin and partly wrong (it claimed the
reports are not scheduled — they are, and default-on); I replaced it with the accurate record.

---

## 1. Nothing drained `revenue_posting_failures`

**The gap, confirmed in code first.** T2-1 created the table so a swallowed revenue-ledger posting stopped being a log
line, and said so in its own migration header: *"nothing in payment-executor drains this table yet … draining them is
the next step."* It never happened. `RestRevenueLedgerClient` swallows every failure **by design** — a ledger outage
must not fail a payment whose money already moved — so a posting that failed simply sat there. The symptom is the
nasty one: booked revenue permanently absent from the ledger, and **every ledger report still balances perfectly**,
because it balances the rows it has.

**Built:** `replay/` + Flyway **V007** (`next_attempt_at`, `last_attempt_at`, `replayed_at`, and a **POISON** status).

Five properties, each with a test that would fail against a naive "loop the pending rows and re-POST" version:

| Property | How | Why it is not obvious |
|---|---|---|
| **Idempotent** | A new `RevenuePostingReplayPort` seam (NOT the write-side client), sending the **stored payload byte-for-byte**, reading revenue-ledger's `201` vs `200` | The write-side client's contract is *swallow everything*. Reusing it would make every replay look successful and clear the table without the money reaching the ledger — the same silent loss, in a new place. `200` = the ledger already had it ⇒ the row is CLEARED, not double-booked |
| **One attempt per row per sweep** | Selection is `status=PENDING AND next_attempt_at <= now`; the re-schedule is persisted immediately after the call | A retry loop inside the sweep is how a partial revenue-ledger outage becomes a full one |
| **Bounded by the EXISTING `attempts`** | No second counter | Hot-path re-failures deliberately share the budget: the column answers *"how many times has this posting failed to land"*, and 8 failures by any route is when a human is needed |
| **Backoff persisted on the row** | Exponential from 60s, capped at 6h, stored in `next_attempt_at` | In-process backoff resets on restart and re-storms a downstream that is still down |
| **Terminal state ALERTS, then stops** | POISON + CRITICAL `REVENUE_POSTING_POISON` through the **existing T3-3 `ops.alert` pipeline** | Poisoning without alerting recreates the silent loss in a new column. Poisoning without *stopping* hammers a permanently broken downstream forever |

Three things poison **immediately** rather than after 8 attempts: a `4xx` that is not 408/429 (revenue-ledger has
judged this exact body invalid and will again — burning the budget only delays the alert by hours), an unmapped
posting type, and a row with no captured payload (nothing to send, so nothing is sent). A hot-path failure can no
longer resurrect a POISON or ABANDONED row — that would hand the replay an unbounded budget and let traffic undo an
operator's decision.

**Ops query:** `GET /internal/ops/revenue-posting-failures` — aggregated in SQL, headline
`outstanding = PENDING + POISON` **with the oldest row's age**, which is the number that separates a blip from an
incident (a count alone cannot). `/rows` is bounded with no offset and deliberately does **not** return the stored
payload. `POST …/replay` runs an attributed sweep through the same wrapper as the cron.

## 2. Day-close (CFO#10)

`dayclose/DayCloseReportService`, `GET|POST /internal/ops/day-close` (+ `/history`), persisted one row per business
date in `day_close_reports` (Flyway **V009**). Upsert, so a re-run is a **correction** of that day, not a second
close — and the version reviewed is the version kept, which is why CFO#10 asked for an artifact rather than an
endpoint. `clean` / `variance_count` / `unresolved_decision_count` / `unavailable_leg_count` are broken-out columns so
a monitor never parses the JSON.

**Three legs.** (a) APPROVED transactions per corridor and per currency, corridors derived from the traffic so a new
corridor needs no registration. (b) The ledger journal **QUOTED** from T2-4's `trial-balance` +
`journal-reconciliation`, verdicts included, never recomputed. (c) Signed prefunding movements over T2-8's
date-ranged endpoint, joined **by txnRef** — with T2-6's `<txnRef>#REFUND-RETAINED@…` slices normalised back to their
transaction, because an exact match would leave every refund unjoined and manufacture a break out of a correct day.

**It reports facts and names variances.** `FLOAT_VS_TRANSACTION_USD`, `TXN_USD_WITHOUT_FLOAT_MOVEMENT`,
`LEDGER_TRIAL_BALANCE_IMBALANCE`, `REVENUE_RECORDED_VS_JOURNALLED`, `REVENUE_NOT_JOURNALLED`, and
`REVENUE_POSTINGS_OUTSTANDING` — the replay backlog from §1, which is the one-sided failure a ledger-only report
**structurally cannot see**: the ledger balances *because* the posting is missing. CFO#6 and CFO#10 turn out to be one
wound seen from two sides, so the two reports point at each other.

**No accounting policy was invented.** A variance whose *treatment* is an open decision is marked
`UNRESOLVED_DECISION` and repeated in `unresolvedDecisions` with the register id and the real question:

- **T2-10** — quoted straight out of revenue-ledger's own `unmappedComponents`, with the amount at stake. Not
  re-derived, so it cannot disagree with the ledger's own report of it.
- **T2-11** — detected from `REVENUE_REVERSAL` **account movement on the date**, not from a refund count, because a
  refund of an earlier date's payment books on *this* date and a transaction-side count would miss it entirely. The
  note quantifies the `RECEIVABLE_PARTNER` double relief (credits − debits) that is T2-11(b)'s measurable shape. This
  is why the ledger leg carries per-account rows and not just per-currency totals: the double relief leaves every
  currency **perfectly balanced** while the receivable drifts, so per-currency totals are blind to it.

Both hold `clean` false while they carry money. Neither is netted away.

**Absence is never zero.** An unreadable leg is `available=false` with a reason and **no figures**, and raises no
variance from numbers it does not have. A paged read that fails part-way **discards the window** rather than tying out
against half a day (half a day produces breaks indistinguishable from real ones while the run looks successful).
`strict=true` answers **409**, matching revenue-ledger's own contract so an automated check need not interpret a body.

## 3. FX exposure (CFO#9)

`dayclose/FxExposureService`, `GET /internal/ops/fx-exposure`, and the closed date's position is embedded in the
persisted artifact. Per currency, from recorded transactions only: collected, refunded, paid out, `netOpenPosition`
(**same-currency net**, so MNT / VND / NPR come out negative — that *is* the exposure), `settledUsd`, and the
**back-derived** `impliedUsdBasis`, plus `missingBasisCount` (transactions with no recorded USD — counted, never
assigned an assumed rate) and `fallbackBasisSuspected` (collections priced at the hub's hardcoded 1350 KRW/USD within
tolerance — CFO#9's second half, using the same back-derivation T2-2's tie-out uses).

**Four things this deliberately is not**, documented in the type so a later reader cannot mistake it: no hedge is
proposed, sized or implemented; there is **no target position**, so no limit and no breach flag; nothing is revalued
at any rate (revaluing would require choosing a rate *and* a policy); and no currency is netted against another
(converting MNT into KRW to net it would mean applying a rate this report has no business choosing).

## 4. Scheduling — default **ON**, and why

All three jobs: `@ConditionalOnProperty(matchIfMissing = true)` plus a shipped `=true`, pinned from both directions by
`LedgerOpsSchedulerDefaultOnTest` (annotation **and** the packaged `application.properties`).

That matches **this service's own** convention, set by T3-3 — `DeclineSpikeMonitor` and `OpsAlertRetentionSweeper`
are both default-on, for the reason T3-3 spelled out: a safety net that has to be switched on is a safety net that is
off in production. The replay **is** the gap; the two reports are strictly read-only (three GETs plus one upsert into
payment-executor's own table), so an unattended run costs a row, not money.

The two default-OFF schedulers nearby are deliberate and do not contradict it: `AuthorizationExpirySweeper` **moves
money** (it releases float holds), and settlement's corridor recon writes a shared ops exception queue and pages
people about corridors nobody has onboarded.

Every run — scheduled or operator — goes through `opsrun/LedgerOpsRunExecutor` into `ledger_ops_runs` (Flyway
**V008**), written `@Transactional(REQUIRES_NEW)` so a FAILED run survives the rollback of the work it describes.
**This is T3-4's `batch_runs` pattern reused, not a second mechanism**; it is a separate table only because
`batch_runs` lives in settlement-reconciliation's database and reaching across service schemas is forbidden by
`INTER_SERVICE_CONTRACTS.md`. **One stated difference from T3-4: no business-day calendar gate.** T3-4's
`BusinessCalendar` is settlement-local, and all three jobs are calendar-**independent** by design — money moves on
Korean banking holidays, so a missing posting must still be replayed and a holiday must still be closed. Inventing a
second calendar would be a second mechanism; consuming settlement's would be a cross-service dependency for a gate
these jobs do not want. That reasoning is in the migration header, not just here.

---

## Tests (67 new)

| Class | Pins |
|---|---|
| `RevenuePostingReplayServiceTest` (7) | one attempt per sweep + persisted backoff gating; a posting that already landed is cleared and never re-sent; budget exhaustion poisons, alerts CRITICAL, and stops (no repeat alert); a 4xx poisons immediately; a payload-less row poisons with **zero** HTTP calls; a hot-path failure does not resurrect a poisoned row; a successful sweep is recorded and attributed |
| `RestRevenuePostingReplayClientTest` (8) | `201` vs `200` vs `204`; 5xx/408/429 transient vs 4xx permanent, with the ledger's own reason carried; unknown type / blank payload make **no** call; never throws |
| `DayCloseReportServiceTest` (9) | the fixture reproduces exactly, including a deliberate 5 USD float gap named with both sides labelled; a tidy day closes clean; **T2-10 + T2-11 appear as UNRESOLVED with their amounts and register ids**; an unreadable leg draws no conclusions; an unconfigured prefunding leg says *why*; a partially-read leg is unavailable; refund slices join back; unmatched transaction USD is named; the replay backlog is on the report; ledger findings are quoted |
| `FxExposureServiceTest` (4) | multi-currency positions and signs; the 1350 fallback detected on the one txn that used it and never applied to another currency; a txn with no USD is *counted*, not assigned a rate; unreadable ⇒ UNAVAILABLE with **empty** positions |
| `DayCloseLegReadsTest` (6) | transactions paged to exhaustion (501 rows over 2 pages); a mid-paging failure discards the day; movements netted signed across pages; a row count disagreeing with `totalElements` discards the window; the ledger leg is quoted incl. `unmappedComponents`; an unreadable ledger throws |
| `LedgerOpsRunDurabilityTest` (3) | a FAILED run carries class/message/bounded trace + alert outcome on one row; `REQUIRES_NEW` on all four recorder methods; rows re-read from a **freshly built** Spring context |
| `DayCloseReportStoreTest` (4) | the artifact round-trips with its `UNRESOLVED_DECISION` treatments and decimals intact; headline columns broken out; a re-run corrects the same row; an unclosed date is **absent**, not an empty report |
| `LedgerOpsSchedulerDefaultOnTest` (5) | all three default-on (annotation + shipped config, not env-defeatable); the replay bound is finite and its backoff sane; **no partner codes ship**; the FX detector is KRW-only |

## Still open — and who owns it

1. **The prefunding leg needs one deployment value.** prefunding is keyed by partner **CODE** and transaction-mgmt
   carries none, so `gmepay.day-close.partner-codes` ships **blank on purpose** — a guessed code would tie the float
   out against the wrong partner and report a variance that is an artefact of the guess. Until
   `GMEPAY_DAY_CLOSE_PARTNER_CODES` is set (likely `SENDMN,GMEREMIT`) that leg is honestly `available=false` with that
   reason. **Needs `deploy/helm/gmepay/values.yaml` and `docker-compose.yml` — other owners.** A test pins that no
   codes ship, so this cannot be "fixed" by guessing.
2. **No distribution.** The artifact is persisted, queryable and exportable, but nothing emails or SFTPs it to finance
   and **nothing alerts on `clean=false`**. The report is produced; the delivery is not built. (An alert on
   `clean=false` would be a small addition here; a delivery channel is not.)
3. **No scheme-confirmation leg.** This is the platform-wide *reporting* layer. Comparing our records against what a
   scheme says it paid remains T2-2's corridor tie-out in `settlement-reconciliation` (another owner), still gated on
   external **O4**.
4. **T2-2's rate-basis variance is still only detected, never booked.** Booking it is the same class of finance
   decision as T2-10/T2-11, not code.
5. **No missed-run detection.** A cron that never fired leaves no `ledger_ops_runs` row, by definition — that needs an
   external alert on `last-success` age (the same residual T3-4 recorded). `ledger_ops_runs` also has no retention
   pruner yet, unlike `ops_alerts`.
6. **No ShedLock.** Safety is by construction — the replay's targets are idempotent on their reference and the close
   is an upsert by date, so two replicas converge — but that is an argument, not a lock.
7. **Never run against a live fleet.** Unit and slice level only; no server or container was started.
8. **`RUNBOOK` entries** for the three new ops surfaces (`revenue-posting-failures`, `day-close`, `fx-exposure`) and
   for what an operator does with a POISON row belong in `Documentation/RUNBOOK_*` — **another owner**.

## Things found in passing, not fixed

- `RevenuePostingFailureStore.record` bumps `attempts` on the hot path, which shares the replay's budget. Per the
  register's own instruction ("bounded attempts with the existing `attempts` counter") this is kept and documented
  rather than split into two counters — but if a posting fails often enough on the money path alone it will poison
  without the replay ever having been the cause. The alert says how many attempts and what the last error was, so the
  distinction is visible to whoever reads it.
- `TransactionResponse` carries no partner code, which is the root of item 1 above. Adding one is a transaction-mgmt
  change (another owner) and would let the day-close derive its own partner list instead of being configured.
