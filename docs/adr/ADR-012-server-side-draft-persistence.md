# ADR-012 — Partner Setup wizard: server-side draft persistence

**Status:** Accepted (user decision, 2026-06-11)
**Slice:** Partner Setup Slices 1–8 (the wizard itself)

## Context
The 8-step Partner Setup wizard (Identity → KYB → Contacts → Banking → Prefunding → Commercial → Schemes → Credentials/Activate) collects ~150 fields across child entities (multiple contacts, bank accounts, schemes, corridors, documents). Operators routinely abandon partway, hand off to a colleague mid-flow, or close the tab. Client-side state would lose all of that on browser crash, hand-off, or session timeout.

## Decision
**Server-side persistence at every step.** Each `Next` button POSTs the step's fields to a step-scoped endpoint (`POST /v1/admin/partners/draft/<draft_id>/step-<n>`). The backend writes the field set into the underlying aggregates in `status=ONBOARDING` and `change_request.state=DRAFT` (ADR-008). The aggregate row already exists — `DRAFT` rows are queryable, resumable, transferable between operators.

URL structure: `/partners/draft/<draft_id>/step-<n>`. Operators can:
- Close the browser at any step and return via "Resume draft" on the partner list.
- Hand off to another operator (4-eyes is enforced at *activation* not at draft editing).
- See partial drafts in audit log with `proposed_by = original_operator`.

The final step calls a **pre-condition gate** that returns `422 + unmet[]` until every required artifact is present. After the activation `change_request` is approved by a different operator (ADR-008), the partner flips to `ACTIVE` and locked fields become immutable.

## Consequences
- Every wizard step is its own audit-log entry. Operators see a draft's full edit history.
- No client-side state library (Redux-Persist etc.) needed for the wizard — Redux holds the *current* step's input buffer only.
- Drafts older than N days (configurable, default 30) get garbage-collected via a scheduled job that closes the change_request with `REJECTED reason='draft expired'`.
- Resume URL is shareable internally (operator-to-operator), with RBAC: only operators with `PARTNER_DRAFT_EDIT` can resume.
