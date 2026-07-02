# GMEPay+ — Feature List, Department Audit ROUND 2 (living)

> Base = the round-1 consolidated `target features 20260701.md` (core §4.1–4.16 + audited groups §4.17–4.22 + CEO launch-readiness layer). This is a **second-pass** audit: each head finds what's STILL missing or NEWLY EXPOSED — not a repeat of round 1.

## What changed since round 1 (audit against THIS state)
- **§4.17 Operations backend is now BUILT** (green + pushed): kill-switch (pause/maintenance/suspend), operational gate in payment-executor, force-resolve UNCERTAIN, operator webhook replay, operator recon re-run, stuck-txn/webhook-backlog/recon-break `OpsAlert` events, and a control-tower aggregation + 360° search + audited operator-action endpoints in ops-partner-bff.
- **Known open threads from that build:** no **admin-ui Operations console** (APIs only — not yet clickable); no **`gmepay.ops.alert` consumer/dashboard** (alerts emitted, nothing receives/pages); `SYSTEM_PAUSED`/`*_SUSPENDED` not yet promoted to the `ErrorCode` enum; alert dedupe/cooldown; runtime base-URL wiring; BFF RBAC hardening.
- **§4.18–4.22 (Finance, Commercial, Architecture, DevOps/SRE, Compliance) remain specified but NOT built.**

## Round-2 Audit Log

### 1) Operations Head (R2) — primitives built, loop not closed → **4.17.R2 hardening**
*Now adequately covered:* real audited manual-recovery APIs, end-to-end emergency containment (kill-switch + gate), control-tower aggregation + 360° search + audit-before-delegate actions.
*Remaining / newly exposed:*
- **Close the alert loop** — `gmepay.ops.alert` has 3 emitters and **0 consumers**; add a consumer + alert store with **ack / snooze / escalate** state + paging (alerts currently fire into a void).
- **Wire the missing alert types** — FLOAT_LOW + DECLINE_SPIKE are in the roster but have no emitter; converge prefunding's own `prefunding.alert` into ops FLOAT_LOW.
- **Alert dedupe / cooldown** — sweeps re-fire every tick → pager storm the moment a consumer exists.
- **admin-ui Operations console** — recovery actions are raw header-carrying POSTs; un-operable under incident pressure.
- **Enforce BFF RBAC** — `ops:operate` is checked only when the permissions header is present and **ALLOWS when absent**; audit records *who*, nothing enforces *whether-allowed*.
- **Controlled re-enable workflow** — unsuspend/resume is a bare toggle; needs maker-checker + health pre-check / canary (turning a compromised partner back ON is riskier than off).
- **Runbook-to-alert linkage + false-positive tuning; searchable operator-action review/attestation surface; kill-switch gameday + shift-handover record + ops MI (MTTR, alert volume).**

### 2) Finance / Treasury (R2) — the new ops actions have money holes → controls + 3 go-live gates
*New financial-control gaps from the just-built ops actions (G1/G2 are effectively correctness bugs):*
- **G1 — force-resolve→REVERSED books NO journal and never releases the held float.** revenue-ledger/prefunding listen to `payment.*`, not the FSM status event; the UNCERTAIN USD hold is never credited back and no reversal entry is written — a financial outcome changes with **zero ledger impact**.
- **G2 — force-resolve→COMPLETED fires the wrong event.** It emits txn-mgmt projection events, not payment-executor's revenue-bearing `payment.approved`; revenue is never recognised (or double-counted if the real event later fires). No idempotency key tying "operator-COMPLETED" to "scheme-paid".
- **G3 — no four-eyes; RBAC degrades open.** One operator both initiates and effects money-changing actions; the guard allows through when the permissions header is absent (concrete SoD violation).
- **G4 — recon re-run can re-book a finance-closed period** with no authorization or period-lock.
- **G5 — no daily tie-out / exception report** reconciling operator resolutions to ledger entries → G1/G2 orphans are invisible at close.
- **G6 — kill-switch pause has no financial checkpoint** (no view of held float / in-flight journals at the pause instant).
*Unbuilt 4.18 go-live gates:* **(1) bank & nostro reconciliation + suspense account** (catches G1/G2 orphans) · **(2) revenue recognition wired to EVERY terminal outcome incl. operator resolutions** (fixes G2) · **(3) SoD / maker-checker on money-affecting operator actions** (fixes G3/G4).

### 3) Business Development (R2) — the ops build is all inward-facing → partner-facing gaps
*Newly exposed by the ops build:*
- **Suspend/pause comms — the trust-destroying surprise.** GME can now cut a partner off but tells them nothing (the operator even types a `reason` that never reaches them); they just start eating 503s. Add auto-notification + a `partner.suspended` webhook event (pipeline is one event-type away): scope, reason, ETA.
- **Partner-facing status / incident page.** `operationalStatus` exists but is operator-only — partners can't tell "is it them or me?"
- **Document the new error codes.** `PARTNER_SUSPENDED`/`SYSTEM_PAUSED`/`ROUTE_SUSPENDED` are bare 503 strings — no catalog entry, no `Retry-After`; partners can't distinguish a suspend from a random error.
- **Partner-facing decline / health transparency** — the ops decline-spike/health signals point inward; exposing the partner's own view is now cheap and is round-1's top retention feature.
- **Commercial-SLA layer for a unilateral suspend** — notice period, emergency-vs-commercial distinction, service-credit accounting when GME's own pause causes downtime.
*Top unbuilt growth levers (reaffirmed):* developer portal + SDKs + self-serve sandbox keys + versioning · self-service trial funnel + go-live cert · flexible/tiered + promotional pricing.

### 4) CTO (R2) — the ops build made three round-1 items load-bearing (code-verified)
- **Two event lineages → operator terminal states invisible to money consumers (root cause of G1/G2).** FSM `TransactionStatusChangedEvent` is always emitted, but `payment.approved` only on the APPROVED branch; `resolveByOperator→REVERSED` emits **only** the FSM event — **no `payment.reversed` / prefund-release domain event exists**. Fix (CTO-owned): an outbox-backed, uniform state→domain-event mapping so every terminal transition emits what consumers need. Pick one lineage.
- **Fail-OPEN kill-switch = safety inversion.** The gate pulls config-registry synchronously per-authorization (3s cache), fail-open default, no circuit breaker — during a registry outage a cold executor lets a **suspended partner transact**. Push/cache the flag (event-driven) + **fail-CLOSED on security flags** (suspension / global pause); fail-open only for availability flags.
- **New `@Scheduled` sweepers × no ShedLock = correctness bug.** ≥2 replicas double-fire (duplicate alerts + double-produced settlement/batch/outbox). Blocker before >1 replica.
- **Alert pipeline half-built** — 3 emitters, **0 consumers**; new event types on the unversioned plain-JSON bus (no schema-registry governance).
- **Control-tower fans out synchronously to 7 upstreams + an N+1 per-partner balance loop, no timeout budget** — tail latency = slowest upstream, exactly during an incident. Needs parallel fan-out + per-call timeouts. (Operator-action idempotency is done well.)
*Go-live gates:* (1) event-contract governance + unified state→domain mapping; (2) resilience + distributed scheduling (ShedLock everywhere; circuit-breaker/timeout on the gate + fail-closed security default); (3) observability (correlation IDs + a real alerting sink).

### 5) DevOps / SRE (R2) — the ops build sharpened three items into blockers (code-verified)
- **ShedLock = hard replica blocker.** 3 new unguarded `@Scheduled` jobs on top of settlement/regulatory; `defaultReplicas:1` is the *only* thing preventing double-fire → the platform is pinned to single-replica (no HA).
- **Alerts detect but can't wake anyone.** `ops.alert` emitted (severity classified) with **no consumer / Alertmanager / pager** — real signal produced and dropped.
- **config-registry is now a pay-path SPOF.** Gate calls it per-auth (3s cache mitigates); registry `replicas:1`, RestClient has **no timeout** (a hung registry stalls auth), and `fail-open:true` **silently disables the kill-switch during a registry outage**.
- **Migrations in-app-on-boot, no rollback** — `V038`/`V009` migrate the kill-switch + operator-audit tables; a bad ops migration downs the incident-control plane itself.
- **Kill-switch has no runbook / console / auth-story / gameday** — RBAC allows when the permission header is absent; pause must take effect within the 3s cache TTL (untested).
- **Sweepers/gate invisible** — no Prometheus scrape/ServiceMonitor (can't alert on "the alerter stopped" or "gate fail-open for 10 min"); sweepers default-disabled with no per-env wiring (silent no-op in prod); new cross-service calls reuse `CHANGE_ME` secrets.
*Go-live gates:* (1) **ShedLock** (blocks any replicas>1); (2) **alerting/paging/on-call** (signal exists, no sink — cheap + blocking); (3) **migration pre-upgrade Job + rollback runbook** (ops schema now touches the control plane). + standing backup/PITR/DR.

### 6) Audit / Compliance (R2) — the new operator powers are an examinable control risk (code-verified)
- **Privileged override, no enforced authorization, no SoD.** `OpsActionController.guard()` **fails open** (allows when the permissions header is absent); one operator both initiates and effects. **The platform already has a four-eyes engine (`ApprovalWorkflowService`) used for config changes — the money-affecting ops actions bypass it.** → fail-closed RBAC + maker-checker + independent review.
- **Force-resolve can mask suspicious activity.** An operator changes a txn's terminal outcome with only free-text reason — no taxonomy, no second approver, no AML check; can complete a txn AML would have held. Needs a rule that a monitored/flagged txn can't be force-resolved without compliance sign-off.
- **Suspend/freeze wired to ops only, not compliance** — no link to sanctions/watchlist or a SAR/freeze workflow; unsuspend has no compliance re-clearance.
- **Recon re-run can silently re-book a closed period** — no period lock / closed-period guard / authorization.
- **Audit is best-effort — never throws** — the money move proceeds even if the hash-chain audit write fails; still no searchable review surface, periodic control-testing, or second-line attestation.
*Go-live gates (sharpened):* **G1 AML/CFT program** (now with a live override that can defeat it) · **G2 examinable control environment for privileged access** (fail-closed RBAC + four-eyes on money-affecting actions + independent review) · **G3 licensing** (freeze / outcome-reversal / period re-book are regulated activities). Also: force-resolve→REVERSED books no GL entry.

### 7) CEO (R2) — round-2 capstone verdict
**Does round 2 change the picture? Yes — for the worse.** Building Ops (4.17) added live, money-affecting risk, not launch-readiness. *Meta-lesson: the team builds the happy-path surface convincingly and skips the invariants — "built" keeps meaning "demos," not "safe."*
**Critical reframe:** four round-2 findings are **defects in code just merged** — unledgered reversals, RBAC-fails-open, split event lineage, no-ShedLock→single-replica. These are **regressions, not backlog**, and they vindicate the round-1 "code-exists vs proven-in-prod" scorecard.
**Top-5 MUST-FIX-NOW (before any new feature work):**
1. **Fix ledger + event lineage together** — operator terminal states emit `payment.*`; REVERSED books a reversing journal + releases the held float; COMPLETED recognises revenue. *No money moves without a ledger entry.*
2. **Fail-CLOSED RBAC + route money-affecting actions through the existing four-eyes engine** (reuse `ApprovalWorkflowService`, don't rebuild).
3. **ShedLock on all schedulers before running >1 replica** — no HA until jobs are single-fire-safe.
4. **Fail-CLOSED kill-switch + config-registry HA + hard timeout** — no transacting during a registry outage; kill the untimed SPOF.
5. **Close the alert loop to a real pager** — add the `gmepay.ops.alert` consumer + paging sink; wire FLOAT_LOW / DECLINE_SPIKE emitters.
**Strategic call:** **FREEZE feature-adding. Harden the base.** No new department features, operator actions, or lanes until the five are fixed, tested, and demonstrated in a multi-replica staging run.

---
## Progress (round 2)
- [x] Operations Head — loop-not-closed; 4.17.R2 hardening
- [x] Finance Head — G1/G2 ledger holes in the new ops actions + 3 go-live gates
- [x] Business Development Head — suspend-with-no-comms + partner status/DX gaps
- [x] CTO — event-lineage split (root cause of G1/G2) + fail-open + no-ShedLock
- [x] DevOps — ShedLock now HARD blocker (single-replica pin) + alerts w/ no sink + registry SPOF
- [x] Audit / Compliance — ops actions bypass the existing four-eyes engine; RBAC fails open; best-effort audit
- [x] CEO — verdict: FREEZE features, fix 5 defects, launch gated on multi-replica staging pass

**ROUND 2 COMPLETE — all 7 heads. Second-pass converged on defects in the just-shipped Ops code, not new features. CEO call: freeze feature-adding, harden the base.**
