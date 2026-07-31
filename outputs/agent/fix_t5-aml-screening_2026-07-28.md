> 작업: T5-3 AML + transaction screening / 출처: agent

# T5-3 — AML monitoring and transaction screening on the payment path

**Status: the seam, the posture, the evidence and the monitoring surface exist and are tested. The
control does not.** Nothing in this change screens anybody against any list. Read
"[What a compliance owner must supply](#what-a-compliance-owner-must-supply)" before this is described
to anyone as an AML control.

---

## 0. Provenance — read this first, the commit history is confusing

Three things happened concurrently on this branch and the history does not make them obvious:

- `2a30738` / `a0083be` — an **interrupted** earlier agent's partial T5-3 seam.
- `d0a4827` — **another agent's** T5-3 seam work, landed while this change was in progress
  (`outputs/agent/fix_t5-aml-seam_2026-07-28.md`).
- `b1b3850` — **a broad sweep commit made by that other agent that swept up THIS change mid-flight**,
  before its tests were written, and labelled it `wip: second, parallel transaction-screening
  implementation (needs reconciliation)`. It also registered T5-3a on that basis.

So most of the code described below is already committed under someone else's commit message, and
**"duplicate" overstates the overlap** — see the T5-3a amendment in `GAP_REGISTER.md`. The two share
one vocabulary already (both sit on the same `lib-kyb` port and provenance types; this change added no
second port). Only the **posture** genuinely collides; persistence, audit, read-path enforcement and
AML monitoring exist in this change and nowhere else. The reconciliation is a merge, not a choose-one,
and §2 below is the recipe.

## 0b. What was already on disk when this started

The branch carried an interrupted WIP commit (`2a30738`) that had landed a partial T5-3 seam:

- **lib-kyb** — `PaymentScreeningPort`, `PaymentScreeningSubject`, `PaymentParty`, `UnscreenedReason`,
  `NoProviderPaymentScreeningPort`, and `ScreeningProvenance.NO_PROVIDER_ID` / `noProvider(caveat)`.
- **payment-executor** — `PaymentScreeningGate` wired into `OperationalGate` and therefore into **both**
  new-payment entry points (`POST /v1/payments/authorize`, wallet `POST /v1/pay`), plus
  `unscreened_payments` (Flyway `V010`), an ops alert, a Micrometer counter and
  `GET /internal/ops/screening-coverage`.

That work is **not re-done here and not modified** — `services/payment-executor` is owned by another
change in flight. What it established, and what this change builds on rather than duplicates, is that
the payment path *already calls* a screening port on every new payment. The gap that remained was what
happens to the answer.

---

## 1. What this change adds

### (a) The posture — `libs/lib-kyb` (new, pure, no Spring)

The WIP left the fail-open/fail-closed decision as a single global boolean,
`gmepay.screening.fail-closed`, defaulting to `false`. That is one decision doing two jobs and it
cannot express the safe default: with a single flag, "off" means no payment is ever refused even when an
owner has decided a party must be screened, and "on" means every payment stops the moment it is set.
It is therefore a kill switch, and it was documented in payment-executor as one.

Three new types split it into the two independent questions it actually is:

| type | what it answers |
|---|---|
| `ScreeningRequirement` | **which parties must be screened.** `EMPTY BY DEFAULT.` |
| `TransactionScreeningPolicy` | **given that one is required, what happens when it cannot be done.** Refuse. |
| `TransactionScreeningEvidence` | what was checked, by whom, when, and what was decided |

The properties that matter:

- **Empty requirement ⇒ nothing is refused.** In the shipped configuration `decide()` returns
  `PROCEED_NOT_REQUIRED` for every party regardless of what the provider said, so merging this changed
  no live behaviour and no existing test expectation. `policy.inert()` reports that state, and the
  startup banner says it in words.
- **Required-but-unavailable ⇒ REFUSE, and that is not configurable to "proceed" in production.**
- **The override is structurally non-production.** `allow-unavailable-override=true` in a production
  environment makes `TransactionScreeningPolicy`'s **constructor throw**, so the service does not start
  rather than starting and quietly permitting. **A blank or unrecognised environment name counts as
  production** — a deployment that failed to say what it is does not get the benefit of the doubt.
- **Using the override always audits.** `PROCEED_UNAVAILABLE_OVERRIDDEN` sets `mustAudit()` and emits
  its own audit verb; money that moved without a required check is never reportable as a clean pass.
- **A `HIT` / `NEEDS_REVIEW` refuses regardless of configuration.** The override governs what happens
  when we know *nothing*, never what happens when we know something bad.
- **An unrecognised party name in configuration fails startup** rather than silently requiring nobody —
  a typo that produced an empty requirement would be indistinguishable from "screening is off".

`TransactionScreeningEvidence` inherits T1-4's guarantee and extends it to the **read** path, which is
the half a `ScreeningResult` constructor cannot cover once a verdict is decomposed into columns:

- a stored `(status='CLEAR', provider_authoritative=false)` pair — reachable by backfill, migration or
  hand-edit — **reads back as `NOT_SCREENED_NO_PROVIDER`**;
- `completedScreening()` is **derived, never stored**. There is deliberately no column to flip;
- the reserved ids (`stub`, `none`, `unknown`) can never read back as authoritative;
- a non-authoritative record with no caveat is given one. Silence is not reassurance.

### (b) The evidence — `services/kyb-adapter`

| | |
|---|---|
| `POST /v1/screening/transaction` | screen a payment's parties, record, audit, report allow/refuse |
| `GET /v1/screening/transaction/{txnRef}` | what was checked for this transaction (404 if nothing was) |
| `GET /v1/screening/coverage` | per-partner, per-reason counts over a window |
| `GET /v1/screening/posture` | which provider answers, what is required, is the override armed |

- **Flyway `V004` `transaction_screening`** — one row per `(txn_ref, party)`, because screening is a
  duty owed per party and a per-transaction row would make "we screen the merchant but never the payer"
  and "we screen nobody" the same state. Verified next free version; no vendor dirs in this module
  (V001–V003 are plain `CREATE TABLE`, matching prefunding's convention).
- **Flyway `V003` `audit_log`** — kyb-adapter had no audit trail at all; T5-1 listed it as
  uninstrumented. Copied from lib-audit's canonical DDL via prefunding's V010. Chain is per
  **transaction reference**, so one payment's screening history is one short chain and a verifier never
  walks an unbounded partner-keyed sequence.
- Audited under three verbs — `TRANSACTION_SCREENED`, `TRANSACTION_SCREENING_REFUSED`,
  `TRANSACTION_SCREENING_OVERRIDDEN` — with a **non-spoofable actor** (`AuditActorResolver`: attested
  human when the internal token *and* `X-Actor` are present, `svc:internal-caller` when only the token
  is, `unverified:` / `unattributed` otherwise, and the bare literal `system` unwritable) and the
  **CHAIN_V2** digest.
- **Every party on every call is recorded, including — especially — when nothing was screened.** A
  trail that only fills up once a vendor is bought cannot evidence the period before it.
- **No subject PII.** No name, DOB, address or nationality column exists; the row stores
  `attributeSummary()` — which attributes were *present*, never their values. A screening-evidence table
  full of payer identities would be a new plaintext PII store (T5-5) created by a control meant to
  reduce risk. A test asserts those columns do not exist.
- Three DB CHECK constraints are the write-path belt for the read-path invariant (a `CLEAR` requires
  authoritative provenance; a non-authoritative row requires a caveat; reserved ids cannot claim
  authority). Each is pinned by a test that attempts the forged INSERT.

### (c) The AML monitoring surface — `services/prefunding`

See §3 below (delivered as a parallel slice).

### (d) What was deliberately NOT written

No thresholds. No structuring or velocity heuristics. No risk scores. No list roster. No match
tolerance. No vendor integration — `OctaKybAdapter` still throws `notYetAvailable()`. No fake sanctions
list: the only object in the change that can return a `HIT` is a **test-source-set** double
(`TransactionScreeningVendorProviderTest.FakeVendorPort`) that exists to exercise the seam, is never
packaged, and is unreachable from any production wiring.

The single judgement encoded anywhere is *"a required check that did not happen is a refusal"*, which
is the definition of the word required, not a policy choice.

---

## 2. Why the seam is in kyb-adapter and not in payment-executor

Because `services/payment-executor` is owned by another change in flight and was not touched. But it is
also the better home on the merits: kyb-adapter already **is** the screening service (it owns the
vendor port, ADR-009/ADR-014), so a single service holds the provider, the evidence and the audit chain,
and payment-executor stays a money-movement service that asks a question rather than one that
half-implements a compliance control.

### Exact payment-executor follow-up

The hook already exists; what it needs is to stop making the decision locally and to send the answer
somewhere durable. In dependency order:

1. **`services/payment-executor/build.gradle`** — nothing to add (`libs:lib-kyb` is already a
   dependency, added by the WIP commit).
2. **New `RestKybScreeningClient implements PaymentScreeningPort`** in
   `services/payment-executor/src/main/java/com/gme/pay/payment/screening/` — `POST` to kyb-adapter's
   `/v1/screening/transaction` with `X-Gme-Internal`, map the response's first matching evidence entry
   back to a `ScreeningResult`. **Two gotchas that will otherwise cost an afternoon:** register it with
   `@Autowired` on the production constructor if you give it a second test constructor (see
   `gmepay-restclient-two-ctor-autowired`), and note `RestClient`'s default request factory rejects
   client-side `PATCH` — not needed here, but the same class of surprise.
   Add `gmepay.kyb-adapter.base-url` to `application.properties`, `docker-compose.yml`,
   `deploy/helm/gmepay/values*.yaml` (release-aware `{{ .Release.Name }}-kyb-adapter`, per T3-10(c))
   and `run-fleet.ps1`.
3. **Register it as the `PaymentScreeningPort` bean** — `PaymentScreeningConfig`'s
   `@ConditionalOnMissingBean` default then steps aside with no other change. Gate it on the base-url
   being set so a local slice with no kyb-adapter still boots on `NoProviderPaymentScreeningPort`.
4. **Replace `gmepay.screening.fail-closed` with `TransactionScreeningPolicy`.** Bind
   `gmepay.screening.transaction.required-parties` / `.allow-unavailable-override` / `gmepay.environment`
   exactly as `TransactionScreeningConfig` does, and have `PaymentScreeningGate` call
   `policy.decide(party, result)` instead of testing its `failClosed` field. This is the substantive
   change: today `fail-closed=false` in production is silently permitted and `fail-closed=true` is a
   global kill switch, whereas the policy makes "required" per-party and makes fail-open in production
   unbootable. **Keep the property name working for one release** or the flag silently changes meaning.
5. **`ScreeningCoverageController` / `unscreened_payments`** — decide whether payment-executor keeps its
   local counter or reads kyb-adapter's `GET /v1/screening/coverage`. Both now exist and **they will
   disagree** the moment one path is exercised without the other; the local counter is the faster
   in-process signal, the kyb-adapter table is the durable regulator-facing evidence. Recommend keeping
   the counter as a metric and deleting the coverage endpoint's *claim* to be the evidence, pointing it
   at kyb-adapter instead.
6. **`e2e-tests`** — one case asserting that a payment with `required-parties` set and no provider is
   refused with `SANCTIONS_SCREENING_UNAVAILABLE` and leaves a `transaction_screening` row plus an
   `audit_log` row.

Until step 3 lands, **no payment is screened and no `transaction_screening` row is written by the live
money path.** The kyb-adapter endpoint is reachable and correct but unexercised. Nothing in this change
should be read as evidence that payments are being screened.

---

## 3. AML monitoring — surfacing evidence, not inventing rules

`services/prefunding` already held the only real velocity data on the platform: the append-only
`cumulative_usage_ledger` (V006) and the `daily_txn_count_limit` cap (config-registry V034). It could
answer "how much cap has this partner consumed in the one period this transaction falls into" — a point
question under a row lock — and nothing else. An investigator's question is "what did this partner's
volume and velocity look like, day by day, over a window", and no query could produce it.

**Delivered** (`services/prefunding/src/main/java/com/gme/pay/prefunding/aml/`):

- `CumulativeUsageLedgerRepository.dailyUsageWindow(...)` — an additive windowed `GROUP BY` over the
  inclusive `[from, to]` KST-day range (lexicographic compare on the zero-padded `yyyy-MM-dd` key, which
  is also what lets `idx_cum_usage_daily` serve it). The four existing point queries are on the money
  path and were **not touched**. Three numbers per day: **net USD** (signed, so a `CUM_REVERSE` nets back
  into the day it was *charged* in), **net txn count** (the velocity arithmetic the cap gate uses), and
  **raw charge count** — the third because a day of 200 charges that were all reversed nets to zero on
  both other measures and would render as an idle day, which is exactly the pattern worth someone's
  attention. A day with no rows produces **no row**; absence of activity is not a zero.
- `GET /internal/v1/prefunding/{partnerId}/aml-monitoring?from=&to=[&evaluate=]` — the per-day rows,
  window totals, and the partner's **configured caps as context, not as rules**. Window size is bounded
  (`max-window-days`, default 366) and an inverted range is rejected, so the read surface cannot be
  turned into a full-table scan.
- `AmlMonitoringRules` — `@ConfigurationProperties("gmepay.aml.monitoring")`, rule list **EMPTY**, and
  an evaluator that with no rules **does nothing and touches no I/O** (pinned by
  `AmlMonitoringNoRulesTest`). When a rule does fire it raises through prefunding's existing outbox
  pipeline plus a `PrefundingAuditor` `AML_MONITORING_RULE_FIRED` row on `AGG_AML_USAGE`, attributed to
  `system:prefunding-aml-monitoring`. Rules fire on strictly-greater-than, so a threshold reads as "up
  to and including this is fine".

**The one number in the package is `max-window-days=366`, a query-size bound, not an AML threshold.**
A malformed rule (blank name, null metric, null/negative threshold, duplicate name, bad window) **fails
startup** rather than being silently skipped, because a rule that quietly does nothing is the same
failure mode as no rule at all. A rule whose `windowDays` exceeds the requested read window is reported
as `notEvaluated` rather than clamped — clamping would score a 30-day rule on 7 days of data and report
a pass it never earned.

Two limitations stated plainly rather than papered over: **de-duplication is in-memory and per
instance**, lost on restart and not shared across replicas (no new table, no migration); and validation
is `InitializingBean` throwing rather than JSR-380, because `spring-boot-starter-validation` is not on
prefunding's classpath and adding a dependency was out of scope.

**No thresholds, structuring heuristics or risk scores were written.** The rule *shape* is defined
(`name`, `metric`, `threshold`, `windowDays`); the *values* are a compliance input. A malformed rule
fails at startup rather than being silently skipped, because a rule that quietly does nothing is the
same failure mode as no rule at all.

---

## What a compliance owner must supply

None of the following can be decided by an engineer, and each is a precondition for calling any of this
a control:

1. **A sanctions/PEP screening vendor**, and with it: which lists (OFAC SDN, EU consolidated, UN, KoFIU,
   domestic PEP register, adverse media), refresh frequency, and the match-confidence threshold at which
   a fuzzy match becomes `HIT` rather than `NEEDS_REVIEW`. Integration is one bean
   (`PaymentScreeningPort` stamping `ScreeningProvenance.vendor(id)`); the *decisions* are not.
2. **Which parties we owe a screening duty to** — `gmepay.screening.transaction.required-parties`. Empty
   today. Naming a party while no authoritative provider is wired refuses every payment involving it;
   that is correct fail-closed behaviour and it is also a decision to stop taking money.
3. **The originator-identity contract change.** Neither payment contract carries a name: API-05
   `authorize` has `customer_ref`, the wallet has `userRef`, both opaque. **A vendor purchase alone
   produces zero screening coverage on the payer** — a provider handed only a customer id can answer
   nothing but "not found", and recording that as clean would be T1-4 one layer down. Counted separately
   as `NO_SUBJECT_IDENTITY`. Closing it means changing the partner-facing API and re-integrating every
   partner, which is a programme, not a ticket.
4. **The disposition workflow for a `NEEDS_REVIEW`** — who reviews, in what tool, within what SLA, and
   what happens to the held payment meanwhile. Today it is simply a refusal with nobody assigned.
5. **AML monitoring rules and thresholds** — the metric, the number, and the window, per rule. Deliberately
   empty. Until populated, prefunding raises no AML monitoring alert.
6. **What an alert obliges us to do** — the STR/CTR filing decision path (특금법), who files, and the
   record-retention period for `transaction_screening` and its audit chain. Note T5-1(viii): there is no
   retention policy on `audit_log` anywhere on the platform.
7. **Whether screening evidence may be retained without column encryption.** This table deliberately
   holds no names, which is a mitigation, not a policy — the platform still has zero column encryption
   (T5-5(d)).

---

## Still open

- **payment-executor is not wired to the evidence store** (§2). The live money path calls a port and
  counts; it does not persist a per-transaction outcome and is not audited. This is the single largest
  remaining item and it is blocked only on module ownership.
- **Two coverage numbers now exist** (payment-executor `unscreened_payments`, kyb-adapter
  `transaction_screening`) and will diverge. §2 step 5 is the reconciliation.
- **payment-executor's `gmepay.screening.fail-closed` still permits fail-open in production silently.**
  The policy that makes that unbootable exists in lib-kyb but is not yet bound there.
- **No vendor.** `OctaKybAdapter` still throws; ADR-014 sandbox credentials still pending.
- **No `GET /v1/screening/integrity` endpoint** on kyb-adapter. The chain is written with CHAIN_V2 and is
  verifiable by lib-audit's `HashChain.inspect`, but unlike config-registry / prefunding / auth-identity
  this module exposes no sweep. Small follow-up; copy prefunding's `AuditChainVerifier`.
- **`V003`'s `audit_log` has no append-only DB trigger** (prefunding/config-registry `V044` equivalent).
  Same H2 limitation, same honest caveat: this is not WORM.
- **`GMEPAY_ENVIRONMENT` is not set in the Helm chart** for kyb-adapter. Unset is treated as production,
  which is the safe direction, so nothing is broken — but a sandbox that wants the override must set it.

## Verification

- `:libs:lib-kyb:test`, `:services:kyb-adapter:test` — green (55 tests in kyb-adapter).
- `:services:prefunding:test` — see §3.
- `gradlew.bat testClasses` — green.
- `check_internal_auth_wiring.py` 94/94 · `check_monitoring_wiring.py` 37/37 ·
  `check_helm_chart_wiring.py` 193/193 · `check_gitleaks_config.py` clean ·
  `docker/keycloak/check-topology.mjs` 101/101.
