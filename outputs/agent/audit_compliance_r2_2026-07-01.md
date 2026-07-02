> 작업: Audit/Compliance round-2 audit / 출처: agent

# GMEPay+ Second-Pass Audit — Control Environment Gaps Exposed by the New Operations Backend
Head of Internal Audit / Compliance / Risk · 2026-07-01

## Scope of this pass
Round 1 identified the compliance group (4.22) as unbuilt. Since then an **Operations backend shipped that grants operators privileged, money- and transaction-affecting powers**, each writing a hash-chained audit row (actor + reason). Round 2 assesses the *control-environment / examinability / AML* gaps those powers newly created. It does **not** re-litigate round-1 findings; it reaffirms only the top go-live gates that the new powers sharpen.

### Newly built privileged actions (verified in code)
- **Force-resolve UNCERTAIN** — operator pushes a stuck txn to COMPLETED (→APPROVED) or REVERSED. `TransactionService.resolveByOperator()` (transaction-mgmt), proxied by `OpsTransactionController.resolve()` (ops-partner-bff).
- **Operator recon re-run** — re-diffs / re-books a settlement batch or an entire business date. `ReconRerunService.rerun()` (settlement-reconciliation), proxied by `OpsActionController.reconRerun()`.
- **Kill-switch** — global pause / maintenance and per-entity **suspend/unsuspend of partner·scheme·route**. `OpsControlService` (config-registry), proxied by `OpsActionController`.
- **Ops control-tower + audited operator-action endpoints** — `ControlTowerController`, `OpsActionController`, `OpsTransactionController`; audit writes via `OperatorActionAuditClient` → auth-identity `/v1/audit/operator-actions`.

### Confirmed peer findings (verified)
- RBAC **degrades open**: `OpsActionController.guard()` returns and **allows the action when `X-Gme-Permissions` is absent** ("No RBAC headers … allow through"). Every ops action routes through this one guard.
- **No four-eyes** on any of these money-affecting actions. `OpsControlService` documents this by design ("Emergency, not 4-eyes"); force-resolve and recon re-run have no second-approver at all.
- **Force-resolve→REVERSED changes a financial outcome with no ledger entry**: `Transaction.applyOperatorResolution()` records only `resolutionReason/resolvedBy/resolvedAt` + FSM transition; no revenue-ledger / GL posting is emitted.
- **Audit write is best-effort, not a gate**: `RestOperatorActionAuditClient.record()` never throws — a failed audit write is logged and the operator action still proceeds.

---

## (a) Control-environment gaps newly exposed by the ops powers

**1. Privileged override with no enforced authorization and no SoD.**
The system records *who / what / why* but nothing enforces *whether-allowed*. `guard()` fails open on a missing permissions header, and a single operator both **initiates and effects** every action (force-resolve, recon re-run, suspend). From an examiner's view, unrestricted money-affecting overrides with no enforced RBAC and no maker-checker are a top-tier finding. Control required: fail-**closed** authorization (reject when the caller/permission cannot be established), enforced RBAC on the ops permission, four-eyes on money-affecting actions, and independent (second-line) review. Note the platform *already has* a four-eyes engine (`ApprovalWorkflowService`) used for config change-requests — the ops actions simply bypass it.

**2. Force-resolve is an examinable transaction override that can mask suspicious activity.**
An operator can manually change a transaction's terminal outcome. A free-text `reason` is required, but there is **no reason taxonomy, no independent review, and no AML check**. This is precisely the override examiners scrutinise: it can be used to *complete* a txn that AML monitoring would have held, or to *reverse/alter* a flagged txn out of view. REVERSED additionally moves value with no ledger entry (audit-integrity gap #3 below compounds this). Control required: structured reason taxonomy, a second approver, and a hard rule that a monitored/flagged/held transaction **cannot be force-resolved without compliance sign-off**.

**3. Suspend/freeze is a compliance action wired only to ops, not to compliance.**
Suspending a partner is operationally an **account freeze**, but `OpsControlService.suspend()` is an ops-only kill-switch: it is not linked to any sanctions/AML watchlist, not tied to a formal freeze/SAR workflow, and **unsuspend has no compliance re-clearance** (any operator can lift it). A real regulatory freeze order cannot be operationalised or evidenced through this path, and a freeze can be silently reversed. Control required: link suspend/unsuspend to the (unbuilt) sanctions/watchlist + a formal freeze/SAR workflow, with compliance-gated unsuspend.

**4. Recon re-run can silently re-book a closed period.**
`ReconRerunService.rerun()` accepts an arbitrary `batchId` **or** `settlementDate` and re-diffs/re-books it with **no period lock, no closed-period guard, and no finance/compliance authorization** — only a log line and the best-effort audit row. This can alter a previously reported / reconciled / filed position after the fact. Control required: period-lock on reconciled/reported dates, and finance + compliance authorization to reopen.

**5. The audit trail is richer but still not examiner-producible or independently reviewed.**
More privileged actions are now logged (hash-chained per ADR-007), which is good, but: the audit write is **best-effort** (action proceeds even if the write fails — a broken chain does not block the money move); there is **no searchable operator-action review surface**, **no periodic control-testing** of these overrides, and **no independent second-line attestation**. Logging is necessary but not sufficient — an unreviewed, non-blocking, non-queryable log is not an examinable control environment.

---

## (b) Top unbuilt 4.22 go-live gates (reaffirmed, sharpened by the new powers)

**G1. AML/CFT program (still absent) — now with a live override that can defeat it.**
There is still no transaction monitoring, sanctions/watchlist screening, or SAR workflow (onboarding KYB screening exists but is not a transaction-AML program). The new force-resolve and suspend powers make this worse, not neutral: operators can now *complete held txns* and *freeze/unfreeze accounts* with no AML linkage. **Blocker.**

**G2. Examinable control environment for privileged access (SoD + enforced RBAC + independent review).**
Go-live requires enforced, fail-closed authorization, four-eyes on money-affecting actions, and independent review of overrides. Today RBAC fails open and one operator initiates+effects. This is a new, concrete instance of the round-1 governance gate — no longer theoretical. **Blocker.**

**G3. Licensing / regulatory authorization to operate the money flow.**
Unchanged from round 1 and still a gate. The new freeze/reversal/re-book powers underscore it: exercising account-freeze and financial-outcome overrides is regulated activity that presumes the operating licence and the supervisory reporting lanes (currently config-entered, not filing-channel-live) are in place.

---

*Cross-references: `OpsActionController` (guard fail-open, four-eyes bypass), `OpsControlService` (suspend not wired to AML; "Emergency, not 4-eyes"), `TransactionService.resolveByOperator` / `Transaction.applyOperatorResolution` (override, no ledger on REVERSED), `ReconRerunService.rerun` (no period lock/authorization), `RestOperatorActionAuditClient.record` (best-effort audit), `ApprovalWorkflowService` (existing four-eyes engine the ops actions bypass).*
