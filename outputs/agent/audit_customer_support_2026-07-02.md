> 작업: Customer Support POV audit / 출처: agent (consolidated)
# GMEPay+ — Customer Support POV audit (2026-07-02)

Grounded in the current code/specs. Actor nuance: GME's direct customers are the **partners**; the payers are the partners' end-users. CS = GME supporting partners' support teams + escalations, and what the platform must expose so a support agent can resolve a payment issue.

## What CS already has
- **Partner self-service portal** — balance, transaction **history + detail** (scoped `WHERE partner_id = token`, 404 on cross-partner), settlement statements (CSV/PDF), a simplified 4-step status trail. Deflects some volume.
- **360° transaction search** (ops-BFF) by `txnRef / partnerId / schemeTxnRef / status / merchantId / date`.
- **Immutable mutation audit** (`hub_audit_log`, insert-only) + auth-event log.
- **Refund/cancel** exists as an action (admin permission `INITIATE_REFUND`).

## Top customer-support gaps
1. **No support-scoped role.** A single `ops:operate` permission gates *everything together* — kill-switch, force-resolve, recon-rerun AND refund. A CS agent would need the dangerous ops permission or get nothing. Need a `support` role: read + safe actions (lookup, raise refund/dispute), NO kill-switch/force-resolve.
2. **No customer-centric lookup.** Search is by GME's `txnRef`/partnerId; CS gets calls with the *end-customer's* identifier (phone/wallet-id), the *partner's own reference*, or amount+date+merchant — none are searchable. Partner-portal detail is keyed on hub `txn_ref` only. CS can't find the txn from what the caller actually has.
3. **No dispute / chargeback / case workflow — entirely absent.** No schema, no UI; refund is admin-only with no request→track flow; the statement "DISPUTED" status has no mechanism to reach it. CS can't raise, own, or track a case with an SLA.
4. **No plain-language status / timeline for CS.** Internal states (UNCERTAIN, SCHEME_SENT) and customer-friendly decline reasons aren't surfaced as a "what happened / where's the money / why it failed" story; the partner 4-step trail is thin for resolving a dispute.
5. **No PII masking + no CS access-audit.** Customer/transaction data is shown unmasked; the audit log covers *mutations*, and query-access to it is OPS/SUPER only — a CS agent viewing customer PII has no masking and no "who-looked-at-whom" logging (privacy/compliance exposure).
6. **No status / maintenance comms for CS.** When GME pauses/suspends (kill-switch), customers flood support with failures, but there's no maintenance banner/status telling CS "we're down / scheme X is out" (only an internal low-balance banner). Ties to the Business-Dev suspend-no-comms gap.
7. **No escalation path.** A CS agent who finds a stuck/UNCERTAIN txn (or a G1-style unledgered case) has no defined handoff to Ops/Finance.
8. **No canned responses / knowledge base / status-explanation mapping.**
9. **No support SLA / ticket response tracking** (only API latency SLAs exist).
10. **i18n is spec-only** — English strings, Korean/other not populated; multi-country customers/partners underserved.

## → New feature group **4.23 Customer Support & Case Management**
Support-scoped RBAC role · customer-centric lookup (phone/wallet-id/partner-ref/amount+date) · CS support console (read + safe actions, distinct from the ops console) · plain-language status + transaction timeline + friendly decline reasons · dispute/chargeback **case management** (raise→own→SLA→resolve→customer comms, wrapping refund) · PII masking + CS access-audit · status/maintenance comms for CS · CS→Ops/Finance escalation · knowledge base / canned responses · support SLA tracking · i18n (Korean/Nepali).
