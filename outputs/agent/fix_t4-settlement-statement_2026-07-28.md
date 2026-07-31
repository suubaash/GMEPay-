> 작업: T4-5 settlement statement / 출처: agent

# T4-5 — the settled record, the partner statement, and making "never transmitted" impossible to misread

Closes what is buildable of **T4-5** in `Documentation/GAP_REGISTER.md`; CPO audit finding **P13**
(`outputs/agent/audit_cpo-product_2026-07-28.md` §P13). Builds on the `batch_runs` / `BusinessCalendar` /
rerun-controller work that landed in the same module hours earlier
(`outputs/agent/fix_t3-batch-and-charts_2026-07-28.md`) — the calendar gate, the run ledger and
`BatchRerunController` are untouched and unduplicated.

**Constraints honoured.** Only `services/settlement-reconciliation`, `services/ops-partner-bff` and
`apps/partner-portal-ui` were touched. No server, container or Docker daemon was started. `revenue-ledger`,
`payment-executor`, `deploy/helm/**` and `scripts/**` were not edited (one **required follow-up lands
outside my scope** — see §7.1, admin-ui).

**Commit provenance — read this before trusting `git log`.** This work is committed inside
**`0885f94` "feat(finance,settlement): revenue-posting replay, day-close + FX exposure, partner statements"**,
which is *another agent's* commit. That agent staged broadly while my edits were in the working tree and
swept all 45 of my files into its own commit; its commit message then described my half of the change
**incorrectly** (it claimed transmission "remains stubbed" and netting "is still dead code" — both false).
That agent noticed and corrected the record in `e521f4b`. I verified with
`git diff HEAD -- services/settlement-reconciliation services/ops-partner-bff apps/partner-portal-ui`
that the committed content is byte-for-byte what I wrote, and nothing of mine is left uncommitted. **There is
no separate commit for T4-5 and there will not be one** — re-committing already-committed files would produce
an empty commit. Only this report and the register entry are mine to commit on top.

---

## 1. What the audit claimed, and what was actually true

Verified in code before changing anything.

| Claim (P13) | Verified | Detail |
|---|---|---|
| `SettlementController` exposes one endpoint that recomputes from unbatched txns | **Yes** | `SettlementService.getSettlements` calls `transactionQueryPort.findUnbatchedApproved(date)` and groups it. It reads **neither** `settlement_batches` nor `settlement_lines`. |
| `RestSettlementClient.detail` returns null → 404 | **Yes, and documented as such** | The method body was a comment plus `return null;`, with a class javadoc explaining there was no upstream endpoint. |
| No date-range query | **Yes** | `recent(partnerId, limit)` had no date parameters at all; upstream took a single `date`. |
| Synthesised `batchId = merchantId-date-type` | **Yes** | `WireSettlement.toSummary()`. Worse than cosmetic: those ids matched no persisted row, so **nothing could ever be looked up by one** — the synthesis and the null `detail` were the same bug seen from two ends. |
| Hardcodes `status=COMPLETED` | **Yes, and `COMPLETED` is not upstream vocabulary at all** | `SettlementBatchStatus` is PENDING/GENERATED/TRANSMITTED/RECEIVED/RECONCILED/ERROR. The word could only ever have been invented in the BFF. |
| `ReconDiffEngine` fast-forwards TRANSMITTED→RECEIVED as bookkeeping | **Yes** | `advanceBatchStatus` did `moveForward(TRANSMITTED); moveForward(RECEIVED);`. |
| Only `SftpTransport` bean is `LocalDirSftpTransport` (temp dir) | **Yes** | Unchanged, and deliberately not touched. |
| `MultilateralNettingCalculator` has no production caller | **Yes** | Repo-wide grep found only `MultilateralNettingCalculatorTest`. |

**Four things the audit did not say, found while working:**

1. **The recon fast-forward was not a shortcut, it was forced.** `GENERATED → RECEIVED` was **not a legal
   edge** in `SettlementBatchStatus`. So the engine had to pass through `TRANSMITTED` to reach a legal
   `RECEIVED`. That is why the lie existed at all, and why deleting the hop alone would have thrown
   `IllegalStateTransitionException` on every recon run.
2. **`settlement_batches.partner_id` is the COUNTERPARTY, not a merchant.** `SettlementBatchFactory` sets it
   to the literal `"ZEROPAY"`; the merchant lives on `settlement_lines.merchant_id`. Any "partner statement"
   built by filtering batches on `partner_id` would have returned one partner **every** merchant's money, or
   nothing. The statement therefore has to be scoped by the LINE, which needs its own query.
3. **`transmitted_at` had a bare public setter** (`setTransmittedAt`) and no relationship to anything else.
   That is precisely the shape by which a never-sent batch acquires a send timestamp.
4. **A batch-level net cannot be a statement line.** `net_settlement_amount` spans every merchant on the
   file, and a DETAIL (ZP0065/0066) batch deliberately has **no** net at all (the job service leaves it unset
   so a trailer total is never read as a net). Per-merchant figures must be re-summed from the lines.

---

## 2. Part 3 first — transmission honesty, because parts 1 and 2 both depend on it

Written first on purpose: a per-batch read and a partner statement are *new places for the old lie to
surface*. The pattern deliberately mirrors T5-2's filing honesty
(`fix_t5-filing-honesty_2026-07-28.md`), so an operator reads one shape across both lanes.

### 2.1 Transmission is a second axis, not a status value

`SettlementBatchStatus` answers **how far reconciliation got**. It was also being asked "did we send it?", and
those are independent facts. A batch can be `RECONCILED` — a confirmation file genuinely arrived and genuinely
tied out against the persisted lines — while GME never sent the request file, because the file is picked out
of a local directory by hand. Both are true; one word cannot hold both.

So `transmission_state` is its own column (V013) with its own vocabulary
(`SettlementTransmissionState`): `NOT_TRANSMITTED`, **`NOT_TRANSMITTED_CHANNEL_UNAVAILABLE`**,
`TRANSMISSION_FAILED`, `TRANSMITTED`.

The distinction between the first two is the point of the enum. *"Not sent yet"* and *"cannot be sent by this
deployment"* are different operational facts, and only the second is a standing gap rather than a pending
task. **The second is the state of every batch in every environment today.** `TRANSMISSION_FAILED` also
requires a live channel, because a deployment with no channel has not *failed* to transmit — it has not
tried, and conflating those would hide a standing gap behind a transient-looking error.

*(Note for anyone reading `Documentation/GAP_REGISTER.md`'s first draft of this entry: `RECONCILED` is **not**
a transmission state. It is a lifecycle status. The two vocabularies are disjoint by design.)*

### 2.2 Three independent layers, because any one alone is bypassable

1. **Config classification** — `SettlementTransmissionChannelRegistry` is configuration-driven only. There is
   no constructor, property or test hook that can make it live without a real endpoint *and* a real
   credential; `noChannelConfigured()` is the honest default state of the platform, not a convenience. Two
   classifications carry the weight: a **local endpoint** (`file:`, `/tmp`, `./`, `local…`, `temp…`,
   `classpath:`) is **not** a channel — that is exactly the `LocalDirSftpTransport` case, which writes to disk
   and sends nothing, and it is the shape that would otherwise let someone "configure" transmission and
   believe it; and a **placeholder credential** (`stub`, `changeme`, `todo`, `replace…`, `dummy`, `test-key`)
   is not an installed credential. An endpoint with no credential is likewise not a channel.
2. **A single write door** — `SettlementBatchEntity.markTransmitted(at, channelId)` is now the **only** way to
   set `transmitted_at` or reach `TRANSMITTED`; the bare `setTransmittedAt` is gone. It demands both
   arguments (a `TRANSMITTED` row with no timestamp, or naming no channel, is not evidence of anything), and
   `markNotTransmitted` **refuses** a sent state so there is exactly one door. `SettlementTransmissionRecorder`
   guards that door with the registry and **throws** `TransmissionChannelUnavailableException` — i.e. always,
   today. Refusing is the point: with no channel there is nothing that could have transmitted, so accepting
   the claim would be fabricating one. This is the same choke-point discipline `ReportFilingService` applies
   to filing `TRANSMITTED`.
3. **The database** — V013's `ck_settlement_batches_transmitted_at` CHECK makes the pairing hold against a
   hand-written `UPDATE`, not just against the application. `SettlementBatchQueryServiceIT` proves it by
   issuing that UPDATE through the real H2 and asserting the constraint fires by name; asserting the
   annotation existed would have proved nothing.

### 2.3 The fast-forward is gone, and the row now carries its own answer

`SettlementBatchStatus` gained the legal edge **`GENERATED → RECEIVED`** (§1 finding 1), and
`ReconDiffEngine.advanceBatchStatus` no longer walks through `TRANSMITTED`. Reconciling an inbound file now
changes **nothing** about our own outbound transmission facts — pinned by two tests in
`ReconDiffForBatchTest` (clean tie-out and discrepancy paths both).

`SettlementBatchJobService` calls `transmissionRecorder.stampReachableState(batch)` at the moment the batch
moves to `GENERATED`, on both the request-file and detail-file paths. So the row records "never sent, and
here is why" **at generation time**, rather than leaving a reader to infer it from a null timestamp. The three
legacy convenience constructors default to a recorder over `noChannelConfigured()` — which is the truth
today, not a shortcut.

### 2.4 What V013 does to history, and what it deliberately does not

Historical rows whose `status` the fast-forward had moved to/through `TRANSMITTED` are reclassified on the
**new** column only, with a note naming V013 as the author. **`status` is not rewritten.** Rewriting settlement
history would make an honest correction indistinguishable from someone editing the books — the same reasoning
T5-1 used when it declined to re-seal historical audit rows. Orphan `transmitted_at` values are nulled
*before* the CHECK is added, so the migration cannot fail on its own legacy data.

---

## 3. Part 1a — the persisted read surface

`SettlementBatchQueryService` (read-only, `@Transactional(readOnly = true)`) plus
`SettlementBatchController` under the existing `/v1/settlements` prefix:

| Endpoint | Answers |
|---|---|
| `GET /v1/settlements/batches?counterpartyId=&from=&to=&status=&limit=` | date-**ranged** persisted batches, newest first |
| `GET /v1/settlements/batches/{batchId}` | per-batch detail + lines + matched/open counts; **404 only for a genuinely unknown id** |
| `GET /v1/settlements/statement?merchantId=&from=&to=&includeLines=` | the partner statement (§4) |
| `GET /v1/settlements/transmission-channel` | the channel board, mirroring `GET /v1/reports/filing-channels` |

Windows are resolved and **bounded**: either bound anchors the other, neither means the last 30 days, and
anything over 400 days is a 400 rather than a partner-decade streamed into memory. An inverted window is a
400, never silently swapped.

**The old endpoint was not deleted** — it is a genuinely useful pre-generation projection. Its javadoc now
says so in the negative: it covers one date so it can never produce a period statement, it has no batch id or
status because it corresponds to no persisted batch, and its figures diverge from the books the moment a
batch is booked (booking applies the partner's Addendum-001 rounding mode and nets cross-date refund
claw-backs; the projection does neither). Consumers that need "what was settled" are told not to read it.

---

## 4. Part 1b — the partner-facing statement

Scoped by **`settlement_lines.merchant_id`** via `findForMerchantInWindow` (§1 finding 2), with per-entry
figures re-summed from that merchant's own lines (§1 finding 4). `SettlementBatchQueryServiceIT` pins the
distinction explicitly: batch `B13`'s own net is 61 000 while MRC-A's share of it is 50 000, and MRC-B's
money appears nowhere in MRC-A's statement.

Σ signed line amount is exactly the net GMEPay+ asked the scheme to credit, so a statement ties to the books
rather than to a re-derivation: the fixture's `50 000 + 34 720 − 4 720 = 80 000` reports as net 80 000, paid
84 720, clawed back 4 720, with the refund line counted as the one open line.

`transmittedEntryCount` and the channel board ride **on the statement itself**, not in a footnote. A partner
must be able to distinguish three things that used to be one word: GMEPay+ booked it, GMEPay+ reconciled it
against the scheme's confirmation, and GMEPay+ transmitted the instruction. Only the first two happen today.

`merchantId` is **required** — a statement with no subject is meaningless, so it is never defaulted to
"everyone". An empty window returns an empty statement rather than an error.

---

## 5. Part 2 — netting: wired as reporting, with the decision named

`NettingReportService` is now the production caller of `MultilateralNettingCalculator`, behind
`GET /v1/settlements/netting?from=&to=`. **Nothing was deleted** and the third state the brief warned about —
looks available but isn't — is what this removes.

**Obligations come from real reconciled data, with no new computation and no FX guesswork.**
`corridor_recon_summary` (V011) already persists `usdOwedScheme` per `(settlementDate, scheme, corridor)`: a
reconciled USD obligation of the hub to that scheme, in the calculator's own sign convention (positive = hub
owes). One `Obligation` per row. Netting happens **within** a counterparty only — the calculator's documented
rule; cross-counterparty netting needs a scheme-level agreement the platform has no basis to assert, and
`NettingReportServiceTest` pins that NINEPAY is never netted against SENDMN.

**Applying it is stated as a decision, not left ambiguous.** Every response carries `applied=false` plus
`REPORTING_ONLY_NOTE`: funding on a netted basis requires a per-counterparty agreement on which obligations
offset, over which window, and who carries the intraday gap — a treasury/commercial decision. No prefunding
call, funding instruction or settlement file consumes these figures.

Two honesty details worth keeping:

- **An empty window reports `nettingEfficiencyPct = null`, not `0`.** No obligations means the ratio is
  undefined; zero would read as "netting achieved nothing", which is a different and false claim.
- **`schemeFeedBackedAll` is reported, not assumed.** It is `false` today because no scheme publishes a recon
  file (external gate O4), so both sides of the obligation derive from GME-owned records. One unbacked row
  makes it false.

Today's honest answer, on the current one-directional corridors, is **0.00 % efficiency** — and that figure
will move on its own the first time a genuinely two-sided corridor is reconciled.

---

## 6. The BFF and the portal — stopping the leak one layer up

**`SettlementStatuses`** (new, mirroring `compliance/FilingStatuses`) is the single normaliser. Three rules:
honest values pass through; absent/unrecognised → `UNKNOWN`, **never** a success; the retired invented
vocabulary (`COMPLETED`, `SETTLED`, `PAID`, `SUCCESS`, `DONE`, `FINAL`) is reclassified to `UNKNOWN` with a
reason, not echoed. An absent `transmissionState` is `UNKNOWN` rather than `NOT_TRANSMITTED`, because "the
service did not tell us" and "the service told us it was not sent" are different, and only the second is a
fact we may report — neither is ever `TRANSMITTED`.

**`RestSettlementClient`**: synthesised ids gone, hardcoded `COMPLETED` gone, `detail` resolves. A stray
`transmittedAt` on a row whose state is not `TRANSMITTED` is **dropped rather than forwarded**. An
unreachable upstream yields an empty statement whose channel reason says *"unreachable … EMPTY because
nothing could be read, not because nothing was settled"* — the distinction a partner needs and a fabricated
zero destroys. The statement's `transmittedEntryCount` is **recounted from the mapped rows**, not trusted
from the field, so the number agrees with what the BFF is itself willing to call transmitted.

**`StubSettlementClient`**: its three fixture batches said `COMPLETED`. In stub mode that fixture *is* what an
operator sees, so it now uses real lifecycle values (`RECONCILED` / `RECEIVED` / `GENERATED`) with real batch
ids and `NOT_TRANSMITTED_CHANNEL_UNAVAILABLE` + reason on every row. A stub presenting a sent settlement
would have reintroduced the exact defect the service-side fix removed.

**New surfaces**: `GET /v1/admin/settlement/batches` (date-ranged),
`GET /v1/admin/settlement/transmission-channel`, and `GET /v1/portal/{partnerId}/settlements` — the last
behind the existing `rbac.requirePartnerScope`, and **read-only on purpose**: partner self-serve settlement
writes are the open T1-5 decision, and a disabled-looking button would be the same defect again.

**Partner portal** gains `/settlements` (`settlementsSlice` + page + nav entry, distinct from `/statement`,
which is the transaction CSV). The page's whole design constraint is that reconciliation must not read as
transmission: the lifecycle chip is never a lone green tick, a separate **"Sent to scheme"** column states
No/Yes from `transmissionState` with the reason on hover and is never derived from `status`, a standing banner
says nothing has been transmitted, and an **absent or `UNKNOWN`** board is treated as not-live rather than
optimistically as available. A failed refresh deliberately does **not** clear `data` — replacing a real
statement with an empty one would read as "nothing was settled".

---

## 7. Verification

```
gradlew :services:settlement-reconciliation:test   → BUILD SUCCESSFUL
gradlew :services:ops-partner-bff:test             → BUILD SUCCESSFUL
gradlew testClasses (repo-wide, --continue)        → BUILD SUCCESSFUL   (was RED on arrival; the
                                                     auth-identity breakage from the concurrent
                                                     audit workstream has since landed)
npx next build (partner-portal-ui)                 → Compiled successfully, /settlements 6.61 kB
npx vitest run (from a COPY at a '+'-free path)    → 24 files / 193 tests passed
python scripts/check_helm_chart_wiring.py          → 193/193
python scripts/check_internal_auth_wiring.py       →  94/94
python scripts/check_monitoring_wiring.py          →  37/37
```

New test counts: `SettlementBatchQueryServiceIT` 15, `SettlementTransmissionHonestyTest` 3 nested classes,
`NettingReportServiceTest` 6, `SettlementStatusesTest` 25, `RestSettlementClientTest` 11 (was 5),
`SettlementDetailControllerTest` 5 (was 2), `PortalSettlementStatementControllerTest` 5, portal page 7.

`SettlementBatchQueryServiceIT` is `@DataJpaTest` against **real H2 migrated by Flyway**, so V013 and its
CHECK constraint are genuinely exercised rather than asserted about. Flyway version verified free before use
(V012 was the newest; this module's migration directory is flat, so there are **no vendor subdirectories to
mirror**).

### 7.1 Follow-up required OUTSIDE my scope

**`apps/admin-ui` still shows a settlement status with no transmission column.**
`src/app/settlement/page.jsx` renders `b.status` in a "Status" column and `src/app/settlement/[batchId]/page.jsx`
renders the drawer. Both now receive honest values automatically (they echo the BFF, so `COMPLETED` is already
gone and real lifecycle values appear), but **neither renders `transmissionState`** — so an operator reading
`RECONCILED` can still take it as "sent". The BFF already serves everything needed:
`transmissionState` / `transmissionReason` / `transmittedAt` on every row, plus
`GET /v1/admin/settlement/transmission-channel` and `GET /v1/admin/settlement/batches?from=&to=`. The admin-ui
owner should add the column, the banner and the date-range filter, exactly as
`fix_adminui-truth-and-sandbox_2026-07-28.md` did for the Reports page. **Until then T4-5's UI half is closed
in the partner portal and open in admin-ui.**

---

## 8. Still open

1. **Real SFTP to ZeroPay/KFTC is externally gated and was not attempted** — scheme credentials plus a
   certification run. `LocalDirSftpTransport` is untouched. The registry classifies a local endpoint as *no
   channel* precisely so pointing configuration at it cannot be mistaken for progress. What exists now is the
   seam (`SettlementTransmissionRecorder.recordTransmitted`) that a real transport plugs into, and it refuses
   until the gate opens.
2. **Applying netting to funding is a DECISION, not code** (§5). Reporting is wired; nothing consumes it.
3. **The netting report's obligations are only as wide as `corridor_recon_summary`** — i.e. the corridor
   lanes that have actually been reconciled. The ZeroPay KRW settlement batches are **not** in it, because
   turning a KRW batch into a USD obligation needs an FX basis decision this report has no business
   inventing. `sourceCorridors` is on the response so a reader can see the coverage rather than assume it.
4. **Nothing schedules or delivers any of these reads.** They are operator/partner-invoked. Same residual
   T2-5 records for the day-close reports.
5. **`SettlementTransmissionRecorder.recordTransmitted` has no production caller** — deliberately, because
   nothing transmits. It is a guard, not a capability: `SettlementTransmissionHonestyTest` proves it refuses.
   Distinguish this from the netting case the brief warned about — a refusing guard advertises nothing,
   whereas the netting calculator advertised a working capability.
6. **`transmittedEntryCount` is always 0 and `nettingEfficiencyPct` is always 0.00** in every current
   environment. Both are correct, and both are the kind of number someone may later "fix" by making it look
   better. The tests assert the honest values.
7. **None of this has been exercised against a running fleet** — unit/slice/contract level, plus `next build`
   and vitest. No server or container was started.
8. **The V013 trigger-free CHECK is not covered on PostgreSQL**, only H2 in PG mode. Portable syntax, but a
   Testcontainers PG slice (`SettlementReconciliationPostgresIT`, `docker`-tagged) is where it should also run.
