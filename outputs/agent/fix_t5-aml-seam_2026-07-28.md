> 작업: T5-3 AML screening seam / 출처: agent

# T5-3 — transaction-level sanctions/PEP screening: the seam, the honest accounting of its absence, and the handoff to compliance

**Scope touched:** `services/payment-executor`, `libs/lib-kyb`, `libs/lib-api-contracts`,
`Documentation/services_backlog/reporting-compliance.md`, `Documentation/GAP_REGISTER.md`.

**No AML rules, thresholds, risk scores, PEP register or sanctions list were invented.** That is the
central constraint of this change, not a caveat on it — see §7.

**Builds:** `:services:payment-executor:test` **473 green** · `:libs:lib-kyb:test` · 
`:libs:lib-api-contracts:test` green · `gradlew testClasses` green fleet-wide. No server, no Docker.

---

## 1. The facts first — what screening exists on the payment path today (verified, not assumed)

The CISO audit said "nothing". That was checked before anything was written, and it is accurate. Two
findings, and **the second one is the more important and was not in the audit**.

### 1a. No counterparty is screened, anywhere

`grep -rniE "sanction|screening|watchlist|\bpep\b|aml"` over
`services/{payment-executor,transaction-mgmt,smart-router}/src` returns **8 hits, none of which is a
screening call**. Every one is the *monetary limit* machinery:

| Hit | What it actually is |
|---|---|
| `PaymentOrchestrator:224` "authorize gate 0 — AML/regulatory" | per-transaction USD cap (`partner_limits` V020) |
| `PaymentOrchestrator:277` "authorize gate 0b (AML cumulative)" | daily/monthly/annual USD + daily txn **count** |
| `PrefundingClient:66` "AML cumulative cap" | the port method for the above |
| `WalletLimitGate:21` "the AML velocity cap (V034)" | a transaction-count ceiling |
| `UsdAmountBasis:27,92` "sanctioned basis" | English, not sanctions — the *approved* USD basis |

So the platform's "AML gates" are **four numeric comparisons against operator-entered ceilings**
enforcing the statutory 소액해외송금업 limits. They consult no list, screen no name and detect no pattern.
The naming is the single most misleading thing in the area and is why this gap keeps being read as
partially closed; §5 fixes the naming everywhere I own it.

The only screening anywhere is the **onboarding-time partner KYB** call
(`config-registry` → `kyb-adapter`, `POST /v1/partners/{id}/kyb/screen`), which screens the licensed
**institution** and its UBOs. A payment's payer and beneficiary are screened **at no point, by
nothing**. And that path has no real provider either (`StubKybAdapter` keyword matching;
`OctaKybAdapter.screen()` throws `notYetAvailable()`) — gap T1-4 stopped its stub `CLEAR` from
masquerading as a completed check but did not make it real.

### 1b. There is nobody to screen — the payment contracts carry no counterparty identity

This is the finding that changes what "fix T5-3" means. Both new-payment entry points were read
field by field:

| Entry point | Party identity available |
|---|---|
| `POST /v1/payments/authorize` (`MpmPaymentRequest`) | `customer_ref` — an **opaque** partner-side handle. No name, no DOB, no nationality, no address. |
| `POST /v1/pay` (`WalletPaymentRequest`) | `userRef` — a wallet account id / user UUID. Same: opaque. |

No sanctions list, PEP register or adverse-media source is keyed by a counterparty's internal customer
id. A name-matching provider handed only a reference can only ever answer "no match" — which is
**indistinguishable from a clean result**, i.e. precisely the T1-4 defect one layer down.

**Therefore: buying a screening vendor would not, on its own, produce any screening coverage on this
platform.** The contract change (partners must start sending the originator's name, and ideally DOB +
nationality) is a partner-integration programme, and it is the long-lead item. Getting this into the
register in those words is, in my judgement, the most valuable single output of this task.

Secondary contract facts recorded for the same reason: the **beneficiary/merchant name is resolved
*after* the gate** (orchestrator step 2 / inside the corridor services), so it is not offered to the
seam rather than being guessed from the QR; and the **wallet path has no caller-supplied payment
reference at gate time**, so its coverage rows carry a null evidence anchor rather than an invented one.

## 2. The seam (task 2) — reusing the T1-4 vocabulary, not a parallel one

`libs/lib-kyb`, which ADR-009 already owns as the vendor-agnostic screening port and which is
deliberately dependency-light so any service can bind to it:

- **`PaymentScreeningPort`** — `screen(PaymentScreeningSubject) → ScreeningResult`, `providerId()`,
  `authoritative()` (defaulting to **false**: a new implementation is presumed non-authoritative until
  it says otherwise, because the failure mode being designed out is an implementation treated as a
  control by default). Contract: must not throw, must not block indefinitely, must not log attribute
  values.
- **`PaymentScreeningSubject`** — `(party, reference, name, countryCode, dateOfBirth)`, every attribute
  nullable **on purpose** (a required-name constructor would force callers to synthesise a placeholder,
  which is how a fake screening subject gets born), plus **`screenable()`** — the structural expression
  of §1b — and `attributeSummary()`, which reports attribute **presence, never values**.
- **`PaymentParty`** — `PAYER` / `BENEFICIARY` / `MERCHANT`. Deliberately excludes the PARTNER: that
  subject is screened on the onboarding path, and conflating the two is the confusion this gap removes.
- **`UnscreenedReason`** — `NO_PROVIDER` / `NO_SUBJECT_IDENTITY` / `PROVIDER_ERROR` /
  `PROVIDER_NOT_AUTHORITATIVE`. Four causes with **four different owners and four different fixes**;
  collapsing them into one boolean is what lets a platform believe it is one vendor contract away from
  compliance.
- **`NoProviderPaymentScreeningPort`** — the default. Not a stub to be improved: the truthful
  implementation of a platform with no vendor. It returns `NOT_SCREENED_NO_PROVIDER` and stamps the new
  `ScreeningProvenance.noProvider(caveat)`. It deliberately does **not** keyword-match the way
  `StubKybAdapter` does, because that is what produced T1-4.
- **`ScreeningProvenance`** gained `NO_PROVIDER_ID = "none"` + `noProvider(caveat)`, and its compact
  constructor now rejects `authoritative = true` for **both** `"stub"` and `"none"`; `vendor(id)` refuses
  all three reserved ids.

**The inherited guarantee is the whole reason for the reuse:** `ScreeningResult`'s own constructor
coerces a `CLEAR` with non-authoritative provenance to `NOT_SCREENED_NO_PROVIDER`. So **no
configuration of this seam can produce a clean screening that did not happen** — including a mis-wired
vendor adapter that claims `authoritative()` but stamps a degraded provenance. That case is tested
(`nonAuthoritativeClear_cannotMasqueradeAsScreened`) and lands as `PROVIDER_NOT_AUTHORITATIVE`.

## 3. Visibility (task 2) — the absence reports itself

`PaymentScreeningGate` (payment-executor), modelled on T3-6's `SchemeOperatingHoursGate`. Composed into
**`OperationalGate.checkNewAuthorization`** — the single call **both** entry points already make — rather
than bolted onto each controller, so gap T4-2's add-a-rule-to-one-entry-point failure cannot recur. It is
evaluated **last** of the three checks (pause → window → screening): no vendor round-trip should be paid
for a payment a pause was going to refuse.

For every unscreened party, four independent signals with deliberately different shapes:

1. **Startup banner** — ERROR level, naming what is absent, the bean to define, and the knob
   (`FixtureSchemeOperatingHoursClient`'s precedent: a missing control may be tolerated, not silenced).
2. **Per-payment WARN** — attribute presence only, never values (no log aggregation, T3-2; no column
   encryption, T5-5).
3. **Per-payment Micrometer counter** — `gmepay.payments.screening.unscreened{reason,party,provider}`,
   un-deduplicated, because a metric is built for volume.
4. **De-duplicated ops alert** on the existing T3-3 pipeline — `PAYMENT_SCREENING_UNAVAILABLE`,
   CRITICAL, **one per (reason, party, UTC date)**. Today *every* payment is unscreened, so a
   per-payment alert would fill `ops_alerts` and be ignored by humans; same call T3-6 made for
   `SCHEME_HOURS_UNVERIFIED`. Test-pinned: **50 payments → 1 alert, 50 counts.** Re-alerts on a new UTC
   date, so the condition is re-surfaced daily rather than silenced forever. An authoritative HIT is
   **not** de-duplicated — every one needs its own disposition.

**Queryable (the "how many went through unscreened?" requirement):** Flyway
`payment-executor/V010__create_unscreened_payments.sql` (next free; this module has a single
`db/migration` dir, no vendor dirs to mirror — verified) + `UnscreenedPaymentCounter` +
`GET /internal/ops/screening-coverage` (covered by the existing wholesale `/internal/**` internal-token
gate, so no new anonymous surface).

Design decisions worth stating because each was a real fork:
- **Aggregate, not row-per-payment**, keyed `(gap_date, reason_code, party_role, provider_id, partner_ref)`
  with an incremented count + first/last timestamps and payment refs as evidence anchors. Today every
  payment is unscreened, so a per-payment table would be an ever-growing duplicate of the transaction
  table carrying no extra information, on the hot authorize path. The per-payment **decision** log
  becomes necessary when a provider is wired and belongs in *that* provider's log with the vendor
  reference and analyst disposition — the migration header says so rather than letting the aggregate
  imply more.
- **`REQUIRES_NEW`** on the increment: the caller's transaction may roll back (limit breach, prefunding
  failure, duplicate-authorize compensation) and the party still went unscreened at the gate. Attaching
  the count to the caller's transaction would under-report exactly the busy, failing periods that matter.
- **Never throws**, so the count is documented as a **lower bound** — stated plainly in the class javadoc
  and in the API response, because it is not transactional with the payment and not hash-chained.
- **No PII columns**, asserted by a test: measuring a compliance gap must not create a new plaintext PII
  store as a side effect.
- **Reads never return a bare number.** Every response leads with `screeningActive` / `providerId` /
  `failClosed` plus an `interpretation` sentence, because "0 unscreened payments" is simultaneously the
  literal truth on a platform that has never screened anything and exactly what perfect compliance looks
  like. `ScreeningCoverageHonestyTest` pins that the no-provider case says
  "NOT... does NOT mean payments were screened" and never uses the words *compliant* / *passed* / *all clear*.

**One real defect was found and fixed while testing this:** `UnscreenedPaymentRepository.increment` is a
JPQL bulk `UPDATE`, which bypasses the first-level cache. Without `clearAutomatically` the row said 7
while every JPA reader in the same persistence context still saw 1 — an **under-count in the one table
whose entire purpose is to state a number honestly**. Caught by asserting the entity read-back, not just
a raw `SUM`; both the fix and the reason are in the repository javadoc.

## 4. Fail-closed (task 3) — the switch exists, off by default, and the asymmetry is deliberate

`gmepay.screening.fail-closed`, **`false`**, absent from every deployed config, ERROR-bannered when armed.
When armed, an unscreened party raises `PaymentScreeningRefusedException` →
**422 `SANCTIONS_SCREENING_UNAVAILABLE`, `retryable=false`**, at the start of the authorization: no float
reserved or deducted, no transaction row, no scheme call, no ledger posting, no event.
`PaymentControllerScreeningTest` proves the "no float, no scheme call" property the only way it can be
proven at that layer — by asserting the **orchestrator is never touched at all** (it owns the quote load,
merchant resolve, PENDING row, prefunding RESERVE and scheme submit) and that no event was published.

**Why the opposite default from the KYB activation gate (T1-4), which *refuses*:**

- **Activation is a one-off, human-paced gate with a safe refusal.** An operator is in a wizard; refusing
  costs a delay on one partner's onboarding, and "then do not go live" is genuinely the right answer.
  Nothing is in flight and nobody is mid-payment.
- **A live payment path is not.** Refusing every payment because no AML vendor is configured would take
  ZEROPAY, NEPAL and SENDMN down instantly and completely, for a control that has **never existed in any
  environment**. Whether to stop taking money has customer, partner and licence consequences: it is the
  owner's and compliance's decision, not a side effect of shipping a seam.

So it is documented as a **kill switch, not a hardening step** — with no provider wired it refuses *every*
payment, and it should only be flipped on a corridor deliberately being stopped. **An authoritative
provider's `HIT`/`NEEDS_REVIEW` is refused *always*, regardless of the flag** (`SANCTIONS_SCREENING_HIT`,
422, non-retryable): the flag governs what happens when we know *nothing*, not when we know something
bad. Honouring a provider's own adverse verdict is not a policy choice. Neither refusal echoes the
matched name or list detail — tipping-off risk, and PII this platform does not encrypt (test-pinned).

**Default off changes nothing** — `defaultPosture_doesNotChangeBehaviour` pins that the request still
reaches the orchestrator, and the full 473-test module suite is green.

## 5. Nothing claims coverage it does not have (task 4)

A repo-wide sweep was run for docs / API fields / UI surfaces / config flags implying transaction
screening or monitoring. Fixed **in scope**:

| Site | Was | Now |
|---|---|---|
| `Documentation/services_backlog/reporting-compliance.md` **WBS 13.8 "AML/KYC hooks & monitoring"** (23 tickets: rule engine, Redis `aml:*` keys, nightly baseline, `/v1/admin/aml/**`, alert queue, STR/SAR export) | reads as a described capability, in a file whose other sections describe built things | ⚠️ **NOTHING IN THIS SECTION IS BUILT** banner naming all of it, plus the two things routinely mistaken for it (limits; onboarding KYB) and what T5-3 actually shipped |
| `libs/lib-api-contracts/KybView` javadoc | screening roster advertised as 3 values (`CLEAR｜HIT｜NEEDS_REVIEW`) and the run described as "completed" — the exact reading that let a stub verdict pass for a real one | four values incl. `NOT_SCREENED_NO_PROVIDER`, stated as **the only reachable outcome of a clean run today**, + the known provenance-field contract gap |
| `PaymentOrchestrator` (class javadoc + gates 0/0b), `WalletLimitGate`, `PrefundingClient` | "AML gate", "AML cumulative", "the AML velocity cap" | each says it is a **limit, not screening** — "They screen nobody, consult no list, and detect no pattern" — and points at the real seam |
| `payment-executor/application.properties` | — | a block headed **"NO PROVIDER IS CONFIGURED"**, so `fail-closed=false` cannot sit there looking like a knob on a working control. Test-asserted. |

**Found and NOT fixed — not my files, listed for their owners** (§8).

## 6. Tests

`PaymentScreeningGateTest` (18) · `UnscreenedPaymentCounterTest` (9, H2 PostgreSQL-mode + real V010) ·
`PaymentControllerScreeningTest` (4) · `ScreeningCoverageHonestyTest` (4). Two existing tests were
mechanically updated for the new gate overload, assertions intact: `WalletPayControllerTest` (5 stubs +
1 verify) and `SchemeOperatingHoursGateTest`'s structural guard — which now asserts *every* public entry
point on `OperationalGate` is still named `checkNewAuthorization` (3 overloads), preserving its real
purpose: protecting the refund/confirm carve-out.

---

## 7. What a real implementation requires — the handoff to compliance

**This is the deliverable of this task.** Nothing below is a code change; every line is a decision or a
procurement/legal item that must be owned by a named person. The seam is ready and none of it is blocked
on engineering.

### 7.1 Vendor / provider — OWNER: compliance + procurement
- **Decide the provider.** Octa Solution is already half-assumed by ADR-014 but its sandbox credentials
  have never arrived and `OctaKybAdapter` still throws. Alternatives are the usual list-screening
  vendors; the choice is not an engineering one. **Note the `Octa Solution AML external partner/` folder
  in this repo is vendored source for a *different* system** — standalone ASP.NET, in no
  `settings.gradle`, in no compose file, called by no Java service, with an empty-host base URL, config
  keys pointing at one developer's `C:\Users\...` path, and a committed SQL Server credential (CISO §4).
  It must be **deleted**, not integrated, and the credential rotated regardless.
- **Required capability, in priority order:** (1) real-time single-subject name screening with a latency
  SLA compatible with a customer-facing payment (the seam's contract requires the adapter to own its
  timeout); (2) sanctions + PEP + adverse media; (3) fuzzy matching with a *configurable* threshold —
  the threshold is a compliance decision and must not be a vendor default nobody chose;
  (4) **ongoing rescreening** of parties against list deltas, which no synchronous call provides;
  (5) an auditable per-decision record with the vendor's own reference (this is the log the V010
  aggregate explicitly does **not** replace).
- **Contract must state list refresh frequency and list provenance**, because the platform will be asked
  to evidence *which* list version cleared a given payment.

### 7.2 List sources — OWNER: compliance
Must be named explicitly in the vendor contract, not left as "sanctions". At minimum for a KR-licensed
remitter operating KR↔NP/MN/VN corridors: **UN Consolidated; US OFAC SDN + non-SDN; EU Consolidated;
UK OFSI; KoFIU/MOFA domestic designations; the destination jurisdictions' own lists; a PEP register
(domestic + foreign + international-organisation, per FATF R.12); adverse media.** Two standing gaps to
close in the same decision: **PEP status is currently self-declared** — `KybJson` writes `isPep` from
operator checkbox input, not from a register — and **no list covers the payer at all today** (§1b).

### 7.3 Rule ownership — OWNER: compliance, with a named individual per rule
Deliberately **not built**, and this is the item most likely to be quietly filled in by an engineer:
- **Screening match threshold** and the false-positive/false-negative posture.
- **Monitoring rules** — velocity, structuring, unusual-corridor, round-amount, dormant-then-active,
  aggregation windows. Every threshold needs an owner, a written rationale (a regulator asks *why 10,000*)
  and a change-control path. Note `partner_limits` / `aml_velocity_*` are **not** these rules: they are
  per-partner licence ceilings, not behavioural detection.
- **Risk scoring / customer risk rating** — none exists for a payment party.
- **Where rules live.** They are configuration, so they belong under config-registry's ADR-008 4-eyes
  change-request flow. CISO §11 notes AML velocity limits are today a **direct single-actor write** with
  no change-request applier — that must be fixed *before* real rules land, or one operator can silently
  widen a detection threshold.

### 7.4 Alert triage workflow — OWNER: operations + compliance
Nothing of this exists; `PAYMENT_SCREENING_UNAVAILABLE` is a *platform-posture* alert, not a case.
- **A case queue** with per-case state (open → investigating → escalated → closed-false-positive /
  closed-true-match), assignee, evidence attachments, and a decision rationale. `ops_alerts` is
  explicitly not this — it has no assignee, no state machine and a 90-day prune.
- **An SLA** for dispositioning a blocked payment, and the **customer-communication script**, bounded by
  **tipping-off prohibitions** — this is why neither the refusal message nor the ops alert carries the
  matched name, and any UI built on top must preserve that.
- **Who may release a blocked payment**, under 4-eyes, with the release recorded in the ADR-007 audit
  chain. Today nothing can release one, because nothing can block one.
- **On-call routing** for `PROVIDER_ERROR`: a vendor outage is an incident with a response, which is
  exactly why it is a separate `UnscreenedReason` from `NO_PROVIDER` rather than hidden inside a
  known-zero baseline.

### 7.5 SAR/STR filing path — OWNER: compliance (GME entity, not the platform)
- **Filing is GME's obligation, not GMEPay+'s**, and the boundary needs writing down: the platform sees
  hub-level partner flows; end-user KYC is the partner's. WBS 13.8-T15 scoped a transaction-data *export*
  to support GME's filing — it does not exist.
- **The KoFIU channel is not live.** Per T5-2, `NOT_FILED_CHANNEL_UNAVAILABLE` is the honest terminal
  state on every lane; the KoFIU endpoint and file spec are still external gates, and
  `StubKofiuTransactionPort` has an empty data source. **So there is currently no path to file an STR
  even if a match were found** — that sequencing must be explicit in the plan, because a working detection
  control with no filing channel is its own finding.
- **Statutory clocks** (STR promptly on suspicion; CTR threshold reporting) need to be stated with the
  retention period for screening decisions and case records — and note V010's coverage table has
  **no retention/pruning by design**, while the future decision log will need a defined one.

### 7.6 The sequencing consequence
The dependency order is **contract → vendor → rules → triage → filing**, not vendor-first. §1b means a
vendor bought today would screen nobody: the partner-API and wallet contracts must carry originator name
(+DOB/nationality) first, and that is a partner-integration programme with external lead time. The
`NO_SUBJECT_IDENTITY` counter is the instrument that will show that programme's progress corridor by
corridor.

## 8. Still open / follow-ups for other owners (NOT edited)

1. **Everything in §7.** No rule, threshold, list or vendor was invented; the gap is not closed and the
   register says so.
2. **`ErrorCode`** — `SANCTIONS_SCREENING_UNAVAILABLE` / `SANCTIONS_SCREENING_HIT` are stable strings via
   the `ApiError(code, …)` ctor, following `OperationalGateException` / `LimitCheckUnavailableException`,
   because `libs/lib-errors` was outside this change's ownership. Promote both to enum members.
3. **`libs/lib-api-contracts/KybView`** — the three T1-4 provenance fields still are not wire fields
   (adding them changes the record's arity and every caller in config-registry + ops-partner-bff).
4. **`apps/admin-ui`** (not my files) — no surface exists for screening coverage; if a compliance page is
   built, it must render `screeningActive`/`interpretation`, never a bare zero.
5. **`services/ops-partner-bff`** (not my files) — `StubConfigRegistryClient` still fabricates `"CLEAR"`
   on its fallback path, and `ComplianceOverviewController`'s `kybStatus` switch still has no
   `NOT_SCREENED_NO_PROVIDER` arm (both carried over from T1-4 §7).
6. **`outputs/docgen/build.js` + the rendered `outputs/feature_spec_artifact.html`** — outside my
   allowed paths, but flagged as the **worst single overstatement found**: exec-facing copy asserting that
   large/suspicious transactions are *detected and filed*. Neither happens. This should be corrected
   before the artifact is shown to anyone.
7. **`smart-router` / `transaction-mgmt`** are un-instrumented: the seam sits on payment-executor's two
   entry points, which is where new payments are born, but a future direct-routing path must call the
   same gate.
8. **The beneficiary/merchant name is resolved after the gate** (§1b) — screening it needs either a second
   call site inside the corridor services or the merchant resolve moved ahead of the gate. Not done,
   because it is a money-path reordering and the payer gap dominates.
9. **kyb-adapter's ongoing rescreening** of LIVE partners (T1-4 §8) is still absent; §7.1(4) is the same
   requirement on the transaction side.
