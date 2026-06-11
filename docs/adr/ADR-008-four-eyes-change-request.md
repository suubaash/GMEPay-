# ADR-008 — 4-eyes maker-checker: in-app `change_request` table + Spring State Machine

**Status:** Accepted (user decision, 2026-06-11)
**Slice:** Partner Setup Slice 1 (foundation) — used in every regulated mutation

## Context
FSS examinations flag any single-operator bank-account / fee / limit / status / credential mutation as a fraud vector. We need maker-checker enforcement on those writes. A workflow engine (Temporal, Camunda) is overkill for what is fundamentally a binary state transition (proposed → approved / rejected) and would add an infrastructure component outside ADR-001..005.

## Decision
A canonical `change_request` table per regulated aggregate, owned by the aggregate's service. Schema:

```
change_request(
  id BIGSERIAL PK,
  aggregate_type VARCHAR(64),         -- 'partner', 'partner_bank_account', 'rule', ...
  aggregate_id  VARCHAR(64),           -- the natural key being changed
  state VARCHAR(16),                   -- DRAFT|PROPOSED|APPROVED|REJECTED|APPLIED
  proposed_by VARCHAR(64), proposed_at TIMESTAMPTZ,
  approved_by VARCHAR(64), approved_at TIMESTAMPTZ,
  rejected_reason TEXT,
  payload_jsonb JSONB,                 -- the proposed new state
  applies_to_field_set TEXT[],         -- which fields this change covers
  CHECK (proposed_by IS DISTINCT FROM approved_by) -- 4-eyes invariant
)
```

State machine in Java via **Spring State Machine** (lib only, no infra). Transitions: `DRAFT → PROPOSED → APPROVED → APPLIED` (or `→ REJECTED` terminal). Only `APPLIED` mutates the aggregate, in one transaction with the audit_log write (ADR-007).

## Consequences
- No new infrastructure. Spring State Machine is a Maven dep.
- The 4-eyes invariant is DB-enforced via the CHECK constraint, not just code.
- `DRAFT` rows back the wizard's server-side persistence (ADR-012).
- Temporal stays available later for cross-service sagas (payment orchestrator, settlement) — NOT used here.
- Reject reasons are mandatory on `REJECTED`; auto-suspend / sanctions-hit auto-rejections set `proposed_by = 'system'` and `approved_by = 'system'` (CHECK exempts equals when both = 'system', codified as a partial constraint).
