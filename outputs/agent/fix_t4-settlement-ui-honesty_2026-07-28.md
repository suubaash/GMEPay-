> 작업: T4-5 settlement UI honesty / 출처: agent

# T4-5 (UI half) — admin-ui can no longer show a settlement file as sent

Closes the **last** residual of **T4-5** in `Documentation/GAP_REGISTER.md` — the one
`fix_t4-settlement-statement_2026-07-28.md` §7.1 named as *"required follow-up OUTSIDE my
scope"*: `apps/admin-ui/src/app/settlement/page.jsx` and `settlement/[batchId]/page.jsx`
rendered a single `Status` column holding `batch.status` and **no transmission fact
anywhere**, so an operator reading `RECONCILED` took it as "sent to the scheme" when in
fact nothing has ever been transmitted.

**Scope honoured.** `apps/admin-ui/**` only. Nothing under `services/**` was read-modified
— `revenue-ledger` and `payment-executor` (another agent's active workstream) were not
touched, and the BFF turned out to be missing **nothing**, so there is no BFF follow-up to
list. No server was started. Files staged individually.

**Verification**
- `cmd /c "cd /d D:\GMEPay+\code\apps\admin-ui && npx next build"` → **compiled
  successfully**, all 40 routes emitted (`/settlement` 2.99 kB, `/settlement/[batchId]`
  2.81 kB, `/settlement/exceptions` 6.75 kB).
- `npx vitest run` from a real **copy** at a `'+'`-free path (`D:\gmepay-adminui-vitest`;
  the vitest `'+'` bug — a junction over the tree is not enough, vite resolves the real
  path. `node_modules` alone can be junctioned back, which is what was done). New/changed
  suites: **43 tests green** (`settlementStatus` 17, `settlementSlice` 6, settlement list
  page 8, batch-detail page 6, exceptions page 7 — was 6). Full suite: **822 passed / 15
  failed of 837** (an earlier run of the same tree gave **818 / 18 of 836** — the failure set
  is not stable, which is the flake signature). Every failure is a pre-existing wizard
  `userEvent`/pointer flake, **no failure is in a file this change touches**, and each
  failing file **passes in isolation** — verified explicitly for `operations` and `approvals`
  (2 passed / 11 tests) since those two are not obviously wizard steps. `npx next lint --dir
  src` → **no warnings or errors**.

---

## 1. Why two columns and not a better single one

The temptation was to relabel the existing column. That would have been wrong, and the
reason is worth keeping: `settlement_batches.status` and `transmission_state` are
**independent facts**, not two precisions of one fact. A batch can be `RECONCILED` — a
scheme confirmation genuinely arrived and genuinely tied out against the persisted lines —
while GME never sent the request file, because today the file is picked out of a local
directory by hand and **a local directory is not a channel**. One column cannot hold both,
which is exactly why V013 gave transmission its own column upstream. So the UI gives it its
own column too:

| Column / field | Answers | May be green? |
|---|---|---|
| **Lifecycle** (`status`) | how far reconciliation got | **never** |
| **Sent to scheme** (`transmissionState`) | did the file leave? | only on `TRANSMITTED` |

**No lifecycle value gets a success colour — including `RECONCILED`, and including the
lifecycle literal `TRANSMITTED`.** That last one is the subtle case and it is deliberate:
`ReconDiffEngine` used to fast-forward every batch through lifecycle `TRANSMITTED` as pure
bookkeeping, and V013 reclassified those rows **on the new column only** — it did not
rewrite `status`, on purpose, so that an honest correction stays distinguishable from
someone editing settlement history. Historical rows therefore still *say* `TRANSMITTED` on
the lifecycle axis, and the one place that word could still deceive is a UI that colours it
green. It renders warning-coloured and labelled `TRANSMITTED (lifecycle only)` with a
tooltip saying it is not evidence a file left.

## 2. The pattern was reused, not reinvented

Per the brief. `src/api/settlementStatus.js` is the settlement twin of
`src/api/filingStatus.js` (T5-2) and `src/components/SettlementTransmissionBoard.jsx` is
the twin of `FilingChannelBoard.jsx`, down to the `data-testid` naming and the
"missing board = unknown, never availability" rule. Same three invariants, transposed:

1. **Green means transmitted and nothing else.** Only the transmission axis can be a
   success, only on `TRANSMITTED`.
2. **Absent or unrecognised is `UNKNOWN`, never promoted.** "The service did not tell us"
   and "the service told us it was not sent" are different facts and only the second may be
   reported — so an absent `transmissionState` renders `UNKNOWN — not verifiable`, *not*
   `NOT_TRANSMITTED`, and never `TRANSMITTED`. This mirrors `filingStatus`'s handling of a
   stale value exactly: it is not re-deriving backend truth, it is refusing to upgrade an
   unknown claim, so a stale service or a cached response cannot paint a green tick. (The
   BFF's `SettlementStatuses` already applies this rule; the UI applies it again rather
   than trusting the field, because the defect being fixed *was* a UI that trusted a field.)
3. **The retired invented vocabulary can never look good.** `COMPLETED` — the literal the
   BFF used to hardcode on every settlement row — plus `SETTLED`/`PAID`/`SUCCESS`/`DONE`/
   `FINAL` render as `… — not a valid state` in warning colour, with a description naming
   where the word could only have come from.

One thing the module does that `filingStatus` did not need: a lifecycle word leaking into
the transmission field (or vice versa) resolves to `UNKNOWN` rather than matching, because
the two vocabularies are disjoint by design and a cross-axis value is uninterpretable.

## 3. What changed, surface by surface

| Surface | Before | Now |
|---|---|---|
| `app/settlement/page.jsx` | one `Status` column = `b.status`; **no** transmission, **no** date window (`/settlement/recent` has no date params) | `Lifecycle` + `Sent to scheme` columns, both chips with tooltips carrying the backend's own reason; `DateRangePicker` + Fetch on `GET /v1/admin/settlement/batches?from=&to=&limit=0`, defaulting to the last 30 days (what upstream anchors an unbounded window to); standing banner |
| `app/settlement/[batchId]/page.jsx` | `Status` field = `batch.status`; nothing else | `Lifecycle status` + `Sent to scheme` fields, `Transmitted at`, `Matched lines`/`Open lines` (the BFF already sent `matchedCount`/`openCount` and nobody rendered them), plus a per-batch banner naming the lifecycle status and stating it is not a send |
| `app/settlement/exceptions/page.jsx` | resolution option labelled **"Resubmit to ZeroPay"** | `"Flag for resubmission (not sent by this platform)"`, plus a dialog note: resolving records a decision against the row and sends nothing, because there is no channel. `resolutionAction` is free text on `ReconExceptionEntity` and **nothing downstream acts on it** — verified before relabelling |
| `store/settlementSlice.js` | `items`/`details` only | `+ listSettlementBatches`, `+ fetchTransmissionChannel`, `channel` (null = unknown). Both axes stored **verbatim** — normalisation belongs to `settlementStatus` alone |
| `api/client.js` | `listSettlements`, `getSettlement` | `+ listSettlementBatches`, `+ getSettlementTransmissionChannel`; JSDoc on all four now states which field answers "did the file leave?" and that `transmittedAt` is null for every batch today |

**Two details that are the whole point of the change:**

- **The banner is data, not a literal.** It renders from
  `GET /v1/admin/settlement/transmission-channel` (`{live, reachableState, reason}`), and
  the reason text shown is the **backend's**, not the UI's. Configure a real channel and the
  board goes green and the banner disappears with no code change. Equally: a *failed* board
  read leaves `channel: null` and renders "not reported — nothing may be assumed
  transmitted", never optimistically as available. A failed board read also deliberately
  does **not** set the page-level `error` — an unknown channel must not replace a loaded
  batch list with a failure, and it is already rendered as unknown.
- **A `transmittedAt` on a batch that is not `TRANSMITTED` is displayed as
  "never transmitted", not as a time.** V013 nulls orphan timestamps and a CHECK constraint
  keeps the pairing, but the UI must not be the one place a stray value becomes evidence.
  Pinned by a test that feeds a real timestamp onto a `NOT_TRANSMITTED_CHANNEL_UNAVAILABLE`
  row and asserts the timestamp does not appear anywhere on the page.

`MATCHED` on a line chip is left green: matched/unmatched is a reconciliation fact about the
scheme's confirmation, not a transmission fact, and the banner above it stops it reading as
delivery. That is a judgement, so it is written into the code as a comment.

## 4. The sweep (brief item 5)

Grepped `apps/admin-ui/src` for `TRANSMITTED` / `RECEIVED` / `RECONCILED` / `COMPLETED` /
`SETTLED` / `sent`, then narrowed to anything settlement-shaped.

- **"Resubmit to ZeroPay"** — the one real hit outside the two target pages. Fixed above.
- `scheme-statements/page.jsx` `statusColor()` maps `SETTLED` → success, and
  `transactions/search`, `components/StatusChip.jsx`, `api/constants.js`,
  `api/txnSearchApi.js` do the same. **Left alone, and it is not the same defect:** that is
  the *transaction* lifecycle (`transaction-mgmt`'s `CREATED…SETTLED`), a real internal
  state, not a claim that a file reached a scheme. The scheme-statements page also already
  frames itself honestly — "hand this to the scheme for reconciliation" says out loud that
  delivery is manual.
- `operations/page.jsx` — `resolution: 'COMPLETED'|'REVERSED'` is a manual-intervention
  outcome, and the "Recon re-run" panel re-runs reconciliation locally. Neither claims a
  transmission. Checked and left.
- `/journal` — no settlement transmission claim.
- `api/reportsApi.js` / `reports/*` `TRANSMITTED` references are T5-2's filing lane, already
  honest, and intentionally untouched.

## 5. Tests

| File | Contents |
|---|---|
| `src/api/__tests__/settlementStatus.test.js` (new, 17) | success colour reachable **only** via `TRANSMITTED` (asserted by enumerating the enum, not by spot-check); absent → `UNKNOWN` not `NOT_TRANSMITTED`; unrecognised and every retired value refuse promotion; no lifecycle value is ever success; `COMPLETED` reads "not a valid state"; `notTransmittedSummary` stands down on a real `TRANSMITTED` but **not** on `RECONCILED`, and makes no claim about an empty list |
| `src/app/settlement/__tests__/page.test.jsx` (new, 8) | the **date-ranged** endpoint is the one called, with a real default window; both column headers exist and the old bare `Status` header **cannot** be found; a `RECONCILED` row shows "Not sent — no channel"; an absent state shows `UNKNOWN`; the banner + board render the backend's reason; a `TRANSMITTED` row **removes** the banner; a failed board read renders unknown and no "channel: live"; the empty state names the window instead of implying nothing was ever settled |
| `src/app/settlement/__tests__/batchDetail.test.jsx` (new, 6) | two separate fields, old `Status` field gone; banner names the lifecycle status and says it is not a send; a stray `transmittedAt` is not shown as a time; absent state → `UNKNOWN` and still banners; a genuine `TRANSMITTED` drops the banner and shows the time; matched/open counts surface |
| `src/store/__tests__/settlementSlice.test.js` (updated, 6, was 2) | contract lock extended with the three transmission fields and `matchedCount`/`openCount`; the ranged thunk lands in the same place; the board stores verbatim; a failed **or non-object** board leaves `channel: null` (junk is never coerced into something truthy that could read as availability); a board failure does not set the page error. Its fixture said `status: 'CLOSED'` — not a value any upstream produces — now `RECONCILED` |
| `src/app/settlement/exceptions/__tests__/page.test.jsx` (+1, 7) | the dialog states resolving transmits nothing, and no option label claims a send to ZeroPay |

## 6. Still open

1. **Real SFTP to ZeroPay/KFTC remains externally gated** (scheme credentials + a
   certification run) — unchanged and untouched by this work. The banner exists precisely
   so the gap is visible until then, and it is driven by config so it will clear itself.
2. **`transmittedAt` has never been non-null in any environment**, so the "shows the send
   time" path is proved by test fixture only. Same class of residual the service-side report
   recorded for `transmittedEntryCount` always being 0 — correct today, and the kind of
   number someone may later "fix" by making it look better.
3. **`/settlement/recent` is still wired in `client.js` and the slice.** Deliberate: the
   list page no longer uses it, but it is a legitimate "newest batches, no window" read and
   deleting an endpoint binding is not this change's business. Its JSDoc now says which
   field answers the transmission question.
4. **The list page has no partner/status filter** — the BFF's `/settlement/batches` accepts
   `partnerId` and the service accepts `status`, so the UI is narrower than the contract. Not
   a honesty gap; a convenience follow-up.
5. **Not exercised against a running fleet.** Unit/component level plus `next build`. No
   server or container was started (a hook reported another session's dev server running in
   this folder; it was left alone).
6. **The full vitest suite still has the 15–18 pre-existing wizard `userEvent`/pointer
   flakes** (the count moves between runs of an identical tree). Not chased, per the brief;
   verified they pass in isolation and that none is in a file this change touches.
