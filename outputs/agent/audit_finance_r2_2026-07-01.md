> 작업: Finance Head round-2 audit / 출처: agent

# Finance / Treasury — Second-Pass Audit (round 2)

Scope: financial-control gaps NEWLY EXPOSED by the built Operations actions (force-resolve UNCERTAIN, operator recon re-run, kill-switch + payment-executor gate, control-tower + audited operator-action endpoints), plus reaffirmation of the 2–3 unbuilt 4.18 items that genuinely gate go-live. Round-1 findings are not re-listed.

Evidence base (verified in code, not memory):
- `services/transaction-mgmt/.../service/TransactionService.java` — `resolveByOperator(txnRef, resolution, reason, operator)`; COMPLETED→APPROVED, REVERSED→REVERSED; records reason/operator on the row (V009), transitions the FSM, saves. No event of its own.
- `services/transaction-mgmt/.../statemachine/TransactionStateMachine.java` (L84–115) — every transition emits `TransactionStatusChangedEvent`; **only APPROVED** additionally emits `PaymentApprovedEvent` + `TransactionCommittedEvent`. REVERSED/FAILED emit only the status-changed event.
- `services/payment-executor/.../web/PaymentController.java` — the revenue-bearing `payment.approved` (margins + serviceCharge, consumed by revenue-ledger) is emitted by `confirmPayment`, NOT by the operator resolve path.
- `services/config-registry/.../ops/OpsControlService.java` — kill-switch; explicitly "Emergency, not 4-eyes"; hash-chain audited only.
- `services/ops-partner-bff/.../web/OpsActionController.java` + `OpsTransactionController.java` — all money-affecting operator actions gate on a SINGLE `ops:operate` permission; `guard()` **allows through when the RBAC header is absent**; audit row is who/what/when/reason only.
- `services/settlement-reconciliation/.../rerun/ReconRerunService.java` — operator recon re-run, any operator with `ops:operate`, any business date.

---

## (a) NEW financial-control gaps from the built ops actions

**G1 — Force-resolve → REVERSED books NO reversal journal and never releases the held float (completeness + integrity).**
`resolveByOperator` with `resolution=REVERSED` drives UNCERTAIN→REVERSED. REVERSED is a terminal FSM state with **no downstream consumer**: the state machine emits only `TransactionStatusChangedEvent`; revenue-ledger and prefunding subscribe to `payment.*`, not to a status change. The prefund USD held/deducted when the txn went UNCERTAIN is therefore **never credited back on the books**, and no reversal (Dr/Cr) entry is written. Net effect: an operator can move a live txn to a terminal "reversed" outcome while the ledger and the float pool still show it as consumed → the GL overstates cost-of-funds and the nostro/float will not tie out. This is a hard completeness hole (a financial outcome changed with zero journal impact).

**G2 — Force-resolve → COMPLETED fires the notification event but NOT the revenue-bearing one; double-book / mis-book risk (completeness + accuracy).**
`resolution=COMPLETED`→APPROVED, so the FSM emits `PaymentApprovedEvent` (partner webhook) + `TransactionCommittedEvent` (FX projection). But these are transaction-mgmt's *notification/projection* events; they carry no margin/serviceCharge and are not the payment-executor `payment.approved` that revenue-ledger books revenue from. So: (i) revenue may **never be recognised** for an operator-completed txn, or (ii) if batch recon later also confirms the same scheme payment and the real `payment.approved` fires, the txn is **double-counted**. There is no idempotency key tying "operator said COMPLETED" to "scheme actually paid," so completeness cannot be asserted either way. An operator can create/omit revenue with no finance-visible ledger entry.

**G3 — No four-eyes on any money-affecting operator action; RBAC degrades open (segregation-of-duties).**
Force-resolve, recon re-run, pause/maintenance/suspend all pass through a single `ops:operate` check, and `OpsActionController.guard()` **returns allow when the permissions header is absent** (documented "local dev / gate off"). One operator both initiates and effects an action that changes financial outcomes (G1/G2) or re-books settlement (G4). There is no maker/checker, no finance sign-off, and no independent authorization for a re-book. SoD on money movement — a round-1 4.18 item — is now concretely violated by live endpoints, not just theoretically missing.

**G4 — Operator recon re-run can re-book/re-diff a closed date with no financial approval and no lock (authorization + period-integrity).**
`ReconRerunService` re-reads the scheme file and re-runs the diff (delete-then-reinsert per batchId) for any `batchId` or whole `settlementDate`, invoked by any `ops:operate` holder. It is idempotent against duplication, but there is **no guard against re-running a date that finance has already closed/tied-out**, and **no finance authorization** for the re-book. A re-run that produces a different break set silently changes the reconciled position of a prior period. Who authorizes a re-book must be a finance decision, gated separately from ops.

**G5 — No daily finance tie-out / exception report over the new privileged actions (detective control).**
Force-resolutions land in the txn row (V009 reason/resolved_by/resolved_at); ops actions land in the hash-chain audit log; control-tower surfaces live counts. But nothing **reconciles operator resolutions to ledger entries** — there is no report of "force-resolved / recon-re-run items today" for finance review, and no assertion that every operator resolution has a matching journal (which, per G1/G2, it does not). The completeness gap is therefore also undetectable at close.

**G6 — Kill-switch pause leaves in-flight settlement/journal state with no reconciliation checkpoint (consistency).**
The payment-executor gate (`operationalGate.checkNewAuthorization`) correctly refuses only NEW authorizes and, by design, lets confirm/cancel/refund of already-authorized txns settle (idempotent replay is deliberately not gated). That is operationally right, but there is **no financial closing step** at pause: authorizations mid-window (AUTHORIZED with a reserved float hold, up to the 15-min TTL) and CONFIRMING txns are neither swept nor reported. Aborted/expired auths release the hold operationally via `voidAuthorization`, but there is no finance-facing statement that "at pause instant, X held float / Y in-flight journals are the open reconcilable set." Resuming mid-window can leave stale reservations that only the TTL clears, with no ledger view of the transient exposure.

---

## (b) Unbuilt 4.18 items that are genuine GO-LIVE gates

**GATE-1 — Bank & nostro reconciliation + suspense account.**
GMEPay+ prefunds partner→GME float and GME→scheme float and settles via ZeroPay files. Without daily bank/nostro recon and a suspense account, unmatched settlement movements (and every G1/G2 orphan above) have nowhere to land and no daily proof that cash on the bank statement equals the ledger. This is the control that would *catch* the new force-resolve holes; it cannot go live without it.

**GATE-2 — Revenue recognition (with the operator-action journals wired in).**
The margin/serviceCharge revenue model exists in-flight but there is no revenue-recognition/GL posting discipline, and G2 shows operator-completed txns bypass the revenue event entirely. Go-live with real partner money requires that every terminal outcome — including operator force-resolutions — deterministically books revenue (or its reversal) exactly once.

**GATE-3 — SoD on all money movement (maker/checker on privileged actions).**
G3/G4 make this concrete: force-resolve, re-book, and revenue-affecting actions are single-operator today. A four-eyes / finance-authorization gate on money-affecting operator actions is a go-live gate for handling regulated customer funds, not a post-launch nicety.

(GL/ERP export + chart of accounts and month-end close are prerequisites to GATE-1/2 but are the mechanism; the three above are the controls that actually block go-live. Treasury multi-ccy/FX-limit/funding-forecast, partner billing/statements, tax, and per-corridor profitability remain important but are not go-live blockers for a controlled launch.)
