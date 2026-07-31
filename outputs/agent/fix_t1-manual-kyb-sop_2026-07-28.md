> 작업: T1-4 manual KYB SOP authority / 출처: agent

# T1-4 — a manual, human-attested sanctions screening is a real screening authority

**The owner's decision, implemented:** partner activation is unblocked by a **compliance-signed
manual KYB SOP**, not by waiting for the ADR-014 vendor and not through the non-production
`gmepay.activation.allow-unscreened-kyb` hatch (untouched, not widened, still `false` by default,
still not the answer).

**Scope:** `libs/lib-kyb`, `libs/lib-audit`, `services/config-registry`,
`services/ops-partner-bff`, `apps/admin-ui`. `services/kyb-adapter` needed **no change** (see §7).
`OctaKybAdapter` still throws `notYetAvailable()` — nothing here pretends the vendor arrived.

**Builds:** `:libs:lib-kyb:test` · `:libs:lib-audit:test` · `:services:kyb-adapter:test` green ·
`:services:config-registry:test` **522** (was 472) · `:services:ops-partner-bff:test` **544**
(was 417 at T1-3, 528 before this change) — all 0 failures. `gradlew testClasses` green fleet-wide.
`npx next build` clean for `apps/admin-ui`. vitest from a real COPY of the tree (the `'+'` path bug):
the four files this change touches pass **51/51** in isolation; the wizard `userEvent` timeouts are
the documented pre-existing flake — see §8. No server, no Docker.

---

## 1. The problem this had to solve, stated exactly

T1-4's first pass was correct and it left the platform unable to onboard anyone:

| Guarantee (kept) | Consequence |
|---|---|
| `ScreeningResult` coerces a non-authoritative `CLEAR` → `NOT_SCREENED_NO_PROVIDER` | the stub can never report a clean screening |
| `ck_partner_kyb_clear_requires_authority` | a clean row with no stated authority is unrepresentable |
| `ActivationGateService` raises `SANCTIONS_NOT_SCREENED`, not overridable by `risk_rationale` | no note can substitute for an un-run check |
| `OctaKybAdapter` throws | **no environment can screen, so every activation legitimately refuses** |

So the gap was never "make the gate pass". It was that **the platform had no way to represent a
screening that a human actually performed.** A compliance officer opening the UN list, searching the
partner's legal names and every declared UBO, and signing off, is a different thing *in kind* from
the stub's keyword match against nothing — but the platform recorded no evidence of it, so the two
were indistinguishable, and treating them the same was the honest choice given the data available.

**What was built is that evidence.** Not a flag, not a second spelling of "authoritative": a
first-class record of who screened, when, under which signed procedure, and what they consulted —
with the authority *bound to the evidence* so an unattested claim remains impossible.

## 2. `libs/lib-kyb` — the authority is evidence-bound in both directions

**`ManualScreeningAttestation(attesterActorId, attestedAt, sopDocumentRef, sopVersion,
sourcesConsulted)`** — every field mandatory, each for a stated reason: no attester ⇒ nobody is
accountable; no instant ⇒ a point-in-time statement about daily-changing lists can never be aged
out; no SOP reference or version ⇒ the unfalsifiable "I checked" this gap exists to remove; no
sources ⇒ no record of what the control covered. Over-long text is **refused, never clamped** — a
shortened record of what was screened is a different claim. `attestedAt` is MICROS-truncated so the
stored TIMESTAMP equals the in-memory value on PostgreSQL and H2.

**`sourcesConsulted` is deliberately free text, not an enum of list names.** This repository does not
know which lists GME's procedure names, and offering a picker would put OFAC/EU/KoFIU into records
where nobody consulted them — the same class of lie as the stub's `CLEAR`, one level down.

**`ScreeningProvenance` gained a fourth component, `attestation`**, and the compact constructor makes
the binding two-way: provider id `manual-sop` **requires** an attestation and requires
`authoritative = true`; an attestation **requires** provider id `manual-sop`. So there is no way to
mint the manual authority without the evidence, and no way to decorate a machine run with a human's
name (which would make a stub run look human-verified). `vendor("manual-sop")` and
`nonAuthoritative("manual-sop", …)` are both refused — those were the two back doors. A 3-arg
convenience constructor keeps every existing call site and every 3-field JSON payload working.

**`ScreeningResult.Status.CLEAR_MANUAL_ATTESTATION`** — and this is the design decision the rest of
the change rests on. `KybView` lives in `libs/lib-api-contracts`, which this change does not own, so
provenance cannot become new wire fields on the wizard's read model. **The status value itself is
therefore the provenance carrier**, exactly as `NOT_SCREENED_NO_PROVIDER` was in the first pass, and
the constructor coerces in both directions:

- a `CLEAR` with manual provenance is coerced **up** to `CLEAR_MANUAL_ATTESTATION` — so the
  distinction survives every hop that carries only the status string (the DB column, the wire DTO,
  the compliance board, the wizard chip) without any consumer having to remember to look;
- a `CLEAR_MANUAL_ATTESTATION` whose provenance is *not* an attested manual screening is coerced
  **down** to `NOT_SCREENED_NO_PROVIDER` — the honest status cannot be borrowed by a producer with
  no attestation behind it (test-pinned for stub / unknown / no-provider / vendor).

The T1-4 stub coercion runs first and is untouched: a non-authoritative `CLEAR` is already honest and
cannot be promoted. A vendor `CLEAR` stays a bare `CLEAR`. A manual `HIT` stays a `HIT` and still
fails closed.

## 3. `libs/lib-audit` — the actor vocabulary, reused not reinvented

`AuditActors.isAttestedHuman(String)` / `requireAttestedHuman(String)`. Deliberately a **separate**
check from `requireAttributable`, not a stricter mode of it: the platform must keep auditing system
actions, and "is this attributable at all" and "is this a person who can be held to it" have
different answers for the same row. `system:<component>` and `svc:<name>` are attributable principals
and are refused here — a scheduler cannot assume compliance liability. The rejection message names
*which* class it refused (`unverified:` = claimed but unproven, `svc:` = a trusted service called in
without forwarding the human) because those are different operator problems with different fixes.

**Why the namespace rule lives here and not in lib-kyb:** lib-kyb is deliberately dependency-light
(`payment-executor` depends on it and *not* on lib-audit — its own build file says so). So lib-kyb's
record enforces presence plus the structural "not a platform principal" shapes, and the vocabulary
check runs at the single write path that mints an attestation. Stated in both javadocs rather than
left as a coincidence, and `ManualScreeningAttestationTest` drives the real `AuditActors` outputs
through the constructor so the two cannot drift.

## 4. `services/config-registry` — the write path, the gate, and the record

**Flyway `V045__partner_kyb_manual_screening_attestation.sql`.** Next free version verified: the
`db/migration` chain ends at V043 and **V044 exists only as a `db/vendor/{h2,postgresql}` pair**, so
V045 is next free across every Flyway location. Engine-neutral (`ADD COLUMN` / `DROP CONSTRAINT` /
`ADD CONSTRAINT` / `CREATE INDEX` only), so **no vendor variant was needed** — the vendor dirs carry
only V004, V023 and V044, and nothing here uses vendor syntax. Five columns
(`manual_attester_actor_id VARCHAR(64)` — same width as `audit_log.actor_id` because it holds the
same value and the two are meant to be joinable; `manual_attested_at`, `manual_sop_document_ref`,
`manual_sop_version`, `manual_sources_consulted`), the roster CHECK extended with
`CLEAR_MANUAL_ATTESTATION`, and two new CHECKs:

- `ck_partner_kyb_manual_clear_requires_attestation` — a manual clean claim must carry authority,
  the manual provider id and all five columns;
- `ck_partner_kyb_manual_attestation_consistent` — the converse, both ways: the columns belong only
  to a manual run, and a manual run (including a manual `HIT`) is never *partially* attested.

`COALESCE` is load-bearing in both, for the reason V042's header gives: a bare
`screening_authoritative = TRUE` yields NULL for an unset column and SQL treats a NULL CHECK
predicate as "not violated" — i.e. the exact row being forbidden slips through on three-valued logic.

**V042's `ck_partner_kyb_clear_requires_authority` is untouched, and nothing historical is
reclassified.** The migration header says so explicitly: V042/V002 already corrected the stub rows
and appended their own explanation to the audit chain (T5-1(c)); an attestation is a new act by a
named human and cannot be back-dated onto partners nobody screened. Every added column is nullable
and every existing row keeps its value — asserted by the migration test.

**`KybService.recordManualScreeningAttestation`** builds the verdict *through lib-kyb* rather than
writing columns directly, so this path gets no private way of spelling a screening status. Three
refusals before anything is written:

- **the actor must be an attested human** → 403. Stricter than the rest of this service on purpose:
  elsewhere an unproven claim is recorded as `unverified:<name>` and made countable (T5-1's
  fail-visible posture, because failing closed would break every operator write). Here the *entire
  content* of the write is a person taking responsibility, so recording it under an unverified name
  would produce an attestation that attests to nothing, and unlike a fee change there is no
  degraded-but-useful fallback value.
- **the outcome roster** is `CLEAR | HIT | NEEDS_REVIEW`. `NOT_SCREENED_NO_PROVIDER` is refused ("an
  attestation that nothing was screened is not an attestation; simply do not record one"), and so is
  `CLEAR_MANUAL_ATTESTATION` — that is the *storage* spelling, and accepting it as input would let a
  caller pick the stored form and bypass the coercion that produces it. Lower case is accepted; that
  would be pedantry, not a control.
- **the typed assertion** must arrive verbatim (`ManualAttestationCommand.REQUIRED_ASSERTION`). A
  boolean would be defaultable by a client; requiring the sentence means the attester sends the
  thing they are asserting, and the wording the UI shows, the wording the API demands and the wording
  quoted in the SOP are one constant rather than three that drift.

The attester and the instant are **not on the request record at all** — the actor comes from
`AuditActorResolver` and the instant from the server clock, so an attestation cannot be signed in
someone else's name or back-dated to before the SOP version it claims to have followed existed.

**Two durability problems that would otherwise have bitten:**

1. `carryForwardScreening` now carries the five columns, so a step-3 full-state replace does not
   strip them. Without it a wizard save would either erase the attestation or be *refused by the new
   CHECK* — the right failure mode but a needless one.
2. `runScreening` / `runVerification` **refuse (409) when an unscreened run would replace a complete
   manual attestation.** Both write a fresh SCD-6 row, so "attest, then click Run screening" would
   have silently downgraded an activatable partner to `NOT_SCREENED_NO_PROVIDER` through the stub,
   with the operator's only clue being the activation gate refusing again later. A *real vendor*
   result is still allowed through — which is the right way round: better evidence should supersede
   an attestation, a run that screened nothing should not. Either way the prior attestation is never
   mutated; it stays on the superseded row as history, and re-attesting is the documented way forward.

**`ActivationGateService`** accepts **exactly two authorities** and refuses everything else: a vendor
`CLEAR` with `screening_authoritative = TRUE`, or a `CLEAR_MANUAL_ATTESTATION` with a complete
attestation. New structured refusal **`SANCTIONS_MANUAL_ATTESTATION_INCOMPLETE`**, distinct from
`SANCTIONS_NOT_SCREENED` because the remedy differs (something *was* done; the record of it must be
completed, not the screening re-run), naming the missing fields, and — like its sibling — **not
overridable by `risk_rationale`**: an attestation that names nobody is not a control, and a rationale
explaining why that is acceptable would be a rationale for having no control. `screeningIsClear()`
accepts both clean values so no call site string-matches the new one.

Completeness is **re-derived from the columns**, not trusted to the CHECK. The CHECK makes the state
unstorable *in this schema* — proved column by column via a native UPDATE — but the gate reads rows,
and a row can arrive from a pre-V045 write, an older dump, or a future migration that adds a status
and forgets a constraint. The cost of trusting the constraint there is a partner going LIVE on an
attestation that names nobody.

**Audit (T5-1).** New verb `PARTNER_KYB_MANUAL_SCREENING_ATTESTED` — its own verb, not a variant of
`PARTNER_KYB_SCREENED`, so a compliance report can list every attestation without filtering machine
runs out of it. Attributed to **the attester**, not to whatever service carried the request.
`KybJson.canonical` appends the five fields (append-only, same discipline as V036/V042, so existing
sealed snapshots keep their bytes) — which means the attester, the SOP version and the sources are
**inside the hash-chained digest**: editing any of them in place now breaks the chain. Pinned by a
test that reads the sealed AFTER bytes and asserts `verifyChain(...) == -1`.

**`ScreeningProvenanceView` + `GET /v1/partners/{code}/kyb/screening-provenance`** — the read model
that carries the detail `KybView` cannot. It derives `satisfiesActivation` server-side (so a UI cannot
draw its own wrong conclusion from a green-looking status) and leads with an `interpretation`
sentence, for the same reason T5-3's coverage endpoint does: a bare status is read as reassurance.
The manual sentence states plainly that it is "a human control under a compliance-signed procedure,
NOT a vendor screening against automated list feeds, and it does not include ongoing rescreening as
lists change."

## 5. `services/ops-partner-bff` — the operator path, and one leak closed on the way

- `POST /v1/admin/partners/{code}/kyb/manual-screening-attestation`, gated `requireOps()`
  (`ops:operate`). The surface-wide `requireAdminWrite()` interceptor is not the question — an
  attestation is a privileged act of a different kind from a wizard save, so it takes the gate the
  platform already reserves for money/state-affecting operator actions. **`ops:operate` is reused
  rather than a new `kyb.attest` code invented**, because a new code must be seeded in
  `auth-identity`'s catalogue and granted to a role (not owned here) and an unseeded permission is
  one nobody holds — the endpoint would be dead rather than protected. Dedicated code = follow-up.
- The attester is `OpsRbacGuard.actor(null)` — the verified token subject, never a body field.
- **`RestConfigRegistryClient` presents `X-Gme-Internal` on this one call**, and it must: config-registry
  cannot authenticate operators itself, so it treats a forwarded `X-Actor` as *attested* only when the
  caller proves itself with the shared secret. Without it the name lands as `unverified:<subject>` and
  the endpoint 403s — correctly. A blank secret makes the client refuse with 503 naming the env var,
  rather than sending a request that upstream will reject for a reason that reads like the operator's
  fault. **Not widened to the other 28 partner/scheme calls** — that is the outstanding T5-1(i)
  follow-up and is a behaviour change across every operator write; this endpoint is new and fails
  closed by design.
- `GET .../kyb/screening-provenance` rides the surface-wide read gate: an operator who can see the
  status must be able to see what it rests on, or a read-only reviewer stares at
  `CLEAR_MANUAL_ATTESTATION` with no way to find out whose attestation it is.
- **`ComplianceOverviewController.kybStatus`** — the T1-4 follow-up the first pass listed. `CLEAR_MANUAL_ATTESTATION`
  → `APPROVED_MANUAL_ATTESTATION` (**not** `APPROVED`), `NOT_SCREENED_NO_PROVIDER` → `NOT_SCREENED`
  (it used to fall into `default -> "PENDING"`, which reads "not finished yet" — but nothing is
  pending: a producer that consults no list ran to completion and screened nothing), and an
  unrecognised value → `UNKNOWN` rather than being resolved in the reassuring direction.
- **`StubConfigRegistryClient` stopped fabricating `CLEAR`** — the other T1-4 follow-up, and the last
  place in the platform that could still mint a clean screening from nothing. Its clean branch is now
  `NOT_SCREENED_NO_PROVIDER`; the keyword rules are unchanged (a smarter fake is a worse fake). It
  also implements the attestation and provenance calls, mirroring config-registry's rules rather than
  a laxer set — with the one difference stated rather than papered over: it has no credential to
  verify, so it refuses a blank actor and takes anything else at face value.

## 6. `apps/admin-ui` — the operator surface, and provenance that survives the last screen

**`src/api/screeningStatus.js`** — one place decides how a screening verdict is worded and coloured,
following the `filingStatus.js` precedent exactly. A vendor `CLEAR` is the only `success` colour;
`CLEAR_MANUAL_ATTESTATION` differs on **all three axes a chip has** (label / colour / filled-vs-outlined
variant, pinned by test) so the two can never be confused; `NOT_SCREENED_NO_PROVIDER` is a warning
labelled "NOT SCREENED — nothing was checked"; an unrecognised value is never upgraded.

The local `SCREENING_CHIP_PROPS` map in `KybForm.jsx` is **deleted**. It knew three values, so
`NOT_SCREENED_NO_PROVIDER` fell through to a grey chip printing the raw enum name — a run that
screened nothing rendered as an unremarkable unknown — and the new value would have done the same.
`DrillDownPanel.jsx`'s second, dumber screening chip and `/compliance`'s `sanctionsColor` are migrated
to the same module, so the vocabulary cannot drift between screens.

**`ManualAttestationDialog.jsx`** — step-3, next to "Run screening". It states plainly what the
operator is asserting ("This does not run a screening. It records that **you** carried out the
sanctions and PEP screening for X by hand…"), that it goes on the tamper-evident trail under their
verified identity, and that a correction is a new attestation. Submission is blocked until the SOP
document, SOP version and sources are filled **and** the assertion is ticked against the full
sentence shown in full — an explicit confirmation, not a Confirm button on a summary. On failure the
dialog stays open so the operator's typed evidence is not discarded.

**Provenance downstream:** the step-3 panel names the attester, instant, SOP document + version and
sources, and flags an `complete: false` attestation as INCOMPLETE rather than rendering it as valid
with blanks; **step-8 `ReviewSection`** — the last screen before an operator proposes activation, and
therefore the worst place for the ambiguity — renders the status through the shared vocabulary plus a
"Manual attestation" row naming whose attestation the activation would rest on; `/compliance` gains
the two board values and filter options, and its fixtures gain the two rows that actually occur on
this platform today (a manual attestation and a not-screened partner), because a fixture set showing
only vendor `CLEAR`s presents a board the platform cannot currently produce.

**"Screening complete" is gone** from the Run-screening success path: it was a lie whenever the run
screened nothing, which is every run today. It now reports what came back and, for an unscreened run,
warns and points at the manual attestation.

**Bug found and fixed in `kybSlice.js`:** both fulfilled reducers keyed the cache off
`action.payload.partnerCode`, and **`KybView` has no such field** (verified in
`lib-api-contracts/KybView` and `KybEntity.toView`). Against a real BFF the cache stayed empty and the
screening panel never rendered at all; the existing tests hid it by injecting fixtures that carry
`partnerCode`. All reducers now key off `action.meta.arg`, with the payload field as a fallback so
those fixtures keep working. `attesting` is a separate flag from `kybLoading` so the two controls do
not disable each other.

## 7. Why `services/kyb-adapter` needed no change

Checked rather than assumed: its `kyb_screening.screening_status` is `VARCHAR(32)` with **no CHECK**
(V001/V002), nothing in the module switches exhaustively on `ScreeningResult.Status`, and no manual
attestation flows through it — the attestation is an operator act recorded against `partner_kyb`, the
regulator-facing record the wizard and the activation gate read, and kyb-adapter is a screening
*provider*. Its 27 tests pass unchanged. Per the task's T5-3a note, none of this touches the
un-reconciled transaction-screening vocabulary (`transaction_screening`, `TransactionScreeningPolicy`,
`prefunding/aml`); this work stays entirely on the partner/KYB authority model.

## 8. Verification, stated honestly

| Requirement | Where it is pinned |
|---|---|
| attested manual screening satisfies activation | `ActivationGateServiceTest#attestedManualScreening_passesTheGate`; `ManualKybAttestationTest#attestedManualScreeningIsAuthoritative` |
| missing attester does not | `ManualKybAttestationTest#onlyAVerifiedHumanCanAttest` (403 for `unverified:` / `svc:` / `system:` / `unattributed` / null, **and no row written**) |
| missing SOP reference does not | `#sopReferenceIsMandatory` (400 × 4 shapes, no row written); `ActivationGateManualAttestationTest#everyMissingFieldRefusesActivation` |
| stub-derived result does not | `#stubScreeningIsStillNotAnAuthority`; `ActivationGateManualAttestationTest#stubStillRefuses`; `ManualScreeningAttestationTest#stubCoercionUnchanged` |
| the attestation is audited and chain-verifiable | `#attestationIsAuditedAndChainVerifies` (verb, actor, sealed AFTER bytes containing the attester + SOP version, `verifyChain == -1`) |
| manual-vs-vendor distinguishable in API and BFF | `#provenanceReadModelIsExplicit`; `PartnerKybControllerTest#manualAttestation_isDistinguishableFromAVendorClear` + `#unscreenedProvenanceIsExplicit`; `screeningStatus.test.js` (label/colour/variant all differ) |
| the DB CHECK still rejects a clean row without authority | `V045ManualAttestationMigrationTest` + `ActivationGateServiceTest#clearWithoutAuthority_isRejectedByTheDatabase` (unchanged) |
| existing `SANCTIONS_NOT_SCREENED` still refuses | `ActivationGateServiceTest#unscreenedKyb_refusesActivation` + `#unscreenedKyb_isNotOverridableByRationale`, unchanged and still passing |

**The one thing that could not be tested through the schema, and why that is stated rather than
hidden:** V045's CHECKs make an incomplete manual attestation genuinely unstorable — the migration
test proves it column by column, and `ActivationGateServiceTest` proves even a native UPDATE cannot
null a column out. So the gate's own completeness check has no reachable row shape in this schema, and
`ActivationGateManualAttestationTest` drives it against a stubbed repository instead. The class
javadoc says exactly that, so a reader does not mistake a mock-based test for laziness.

**SPA flake, measured not asserted.** Full-suite vitest on a real copy: **22 failures / 555 tests**
across the wizard step-1/2/4/7/8 forms and 3 pre-existing KybForm cases, every one a `userEvent`
type-then-click timeout, in files this change does not touch. A HEAD baseline on the same copy
measured 13 — the count moves with machine load, not with this diff. **All four files this change
touches pass 51/51 in isolation, including all 6 new tests**, and `next build` is clean.

**Not proven:** nothing has been exercised against a running fleet. The internal-auth handshake on the
attestation call, the RBAC 403, and the end-to-end wizard → BFF → config-registry → activation path
are reasoned and unit/slice-tested, not observed.

---

## 9. WHAT THE SOP DOCUMENT MUST CONTAIN — the human half of this decision

The code now **requires** an SOP reference and version and refuses activation without them. It cannot
check that the referenced document is any good. That is compliance's half, and it is the half that
makes this control real rather than a field in a form. The document must contain, at minimum:

1. **Scope and trigger.** Which partners it applies to (all, or by type/jurisdiction/risk band), and
   at which points it must be run: initial onboarding before activation, on a UBO or control change,
   on a legal-name change, and on a stated periodic cadence. State explicitly that it is the interim
   control **in place of an automated screening vendor**, and that it ends when the vendor lands.
2. **The subjects to be screened.** Named, not implied: the partner's local and romanized legal
   names, former/trading names, tax id, **and every declared UBO** (name, DOB where held,
   nationality, country of residence) — the platform stores exactly these, and the SOP must not
   require data the platform does not hold or omit data it does.
3. **The sources to be consulted, by name and by version/date.** UN consolidated, OFAC SDN *and*
   non-SDN, EU consolidated, UK OFSI, the KoFIU/MOFA domestic lists, the destination-jurisdiction
   list for each corridor the partner will run, a PEP register satisfying FATF R.12, and an
   adverse-media search. For each: where it is accessed, and how the attester records **which
   snapshot/date** they searched. This list is compliance's to set — the platform deliberately does
   not offer a picker, because a picker would put list names into records where nobody consulted them.
4. **The matching method.** Exact and transliteration/alias variants to try, how Korean↔romanized
   name variance is handled, what counts as a match versus a near-match, and the disposition rule for
   a near-match (→ record `NEEDS_REVIEW`, not `CLEAR`).
5. **The evidence to retain.** Which screenshots/exports are kept, where, and for how long (ADR-007
   says 5–10 years for the audit record; the attester's working papers need their own answer). The
   platform stores the attester's *summary* of what was consulted, not the search output — the SOP
   must say where the output lives, because a regulator will ask to see it.
6. **Who may attest, and who may not.** A named role (e.g. MLRO or a delegate they name in writing),
   and an explicit statement that the person who performed the screening is the person who attests.
   Note the platform enforces "a verified human", **not** four-eyes: if compliance wants a second
   reviewer, that is a process step the SOP must define and a follow-up for the code (see below).
7. **What the attester must type into the platform**, quoted so the two agree: the SOP document
   reference and the exact version string, and a `sourcesConsulted` entry naming the lists searched
   and the date/snapshot. Quote the assertion sentence verbatim —
   *"I performed this sanctions and PEP screening myself, following the SOP named above, and I am
   accountable for the result."* — so the officer knows before they start what they will be signing.
8. **Version control and re-attestation.** How the document is versioned, who approves a revision,
   and — load-bearing — **what happens to partners attested under a superseded version**: whether
   they must be re-attested, and by when. The platform records the version precisely so this question
   is answerable; the SOP must answer it.
9. **What this control does NOT cover.** Written down, because the platform says it too and the two
   must not disagree: no ongoing rescreening as lists change between attestations; no transaction-level
   screening of payers or beneficiaries (T5-3/T5-11 — a payment carries no party identity at all); no
   automated feed. The escalation path when a HIT is found, and the fact that **no STR can currently be
   filed** because the KoFIU channel is not live (T5-2).
10. **Sign-off.** The compliance owner's name, role, signature and date, and the review date. This is
    what makes it "compliance-signed"; without it the platform is recording a reference to a draft.

**Until this document exists and is signed, an attestation recorded through the new endpoint is
honest about who and when but references a procedure nobody approved.** The code cannot detect that,
and this section is the checklist for closing it.

## 10. Follow-ups (files not owned here, or decisions)

| Item | Owner / note |
|---|---|
| **Four-eyes on an attestation** | the platform requires a verified human, not a second approver. `auth-identity`'s existing RBAC approval workflows are the mechanism; whether compliance wants it is a policy decision (see SOP §6). |
| **A dedicated `kyb.attest` permission** | `ops:operate` is reused because a new code must be seeded in `auth-identity` V00x and granted to a role — not owned here, and an unseeded permission protects nothing. |
| **Attestation expiry / re-attestation sweep** | `manual_attested_at` and `manual_sop_version` are stored precisely so an ageing attestation is detectable, but **nothing ages one out**. Needs the SOP's cadence (§8) before it can be built, and then a scheduler + an ops alert. |
| **T5-1(i): widen the BFF's internal-auth to all config-registry writes** | done for the attestation call only. Flipping the other 28 operator writes from `unverified:<name>` to attested is that gap's own follow-up, after which `gmepay.audit.actor.require-attestation=true` becomes possible. |
| **`StubBusinessRegistrationVerifier` still returns `VERIFIED` with no provenance** | the same class of gap one level down (first pass §8.3), untouched. It no longer matters for activation (the screening branch blocks first) but `biz_reg_status = VERIFIED` on a stub run is as unearned as `CLEAR` was. |
| **`outputs/docgen/build.js` + the rendered artifact** | another owner's tree. The KYB paragraph should now say activation is unblocked by an attested manual SOP screening, not that it simply refuses. |
| **`deploy/helm/**` + `docker-compose.yml`** | another owner's tree. No new config is required by this change — but the BFF needs `GMEPAY_INTERNAL_AUTH_SECRET` set for the attestation call to succeed, and it is already set on both surfaces (verified, not assumed). |
| **kyb-adapter still has no PostgreSQL**, `OctaKybAdapter` still throws | unchanged external gates (ADR-014, backup inventory). |
