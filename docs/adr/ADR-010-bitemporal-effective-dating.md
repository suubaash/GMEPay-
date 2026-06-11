# ADR-010 — Bitemporal effective dating for partner-domain aggregates

**Status:** Accepted (user decision, 2026-06-11)
**Slice:** Partner Setup Slice 1 (foundation) — applies to Partner, Rule, fee schedule, bank account, contract, scheme enablement

## Context
A 20-year system must answer two distinct questions about any historical fact: (a) "what was true on date D in business terms" and (b) "what did we record about date D as of recording-time T". Examples:
- A back-dated regulatory correction lands today (T) saying partner X's fee schedule was wrong on date D three weeks ago — we must keep both views for audit.
- A historical settlement must re-price correctly against the rule rows in effect on the original txn business date, even if those rules have since been changed.

The current `PartnerEntity.effective_from / effective_to` is unitemporal — only business time, no transaction-time dimension. That fails the audit case.

## Decision
Every regulated partner-domain table gets four timestamps:
- `valid_from`, `valid_to` — **business time** (when the fact was/is true)
- `recorded_at`, `superseded_at` — **transaction time** (when we wrote it; NULL on current row)

Storage pattern: **SCD Type 6 with current-flag**. The aggregate table holds both the current and historical rows; rows are never `UPDATE`d — every change is a paired `(UPDATE prior_row SET superseded_at=now()) + (INSERT new_row)` inside one transaction. Current view = `WHERE superseded_at IS NULL`. As-of view = `WHERE valid_from <= D AND (valid_to IS NULL OR valid_to > D) AND recorded_at <= T AND (superseded_at IS NULL OR superseded_at > T)`.

## Consequences
- Doubles row count over the lifetime of the system. Acceptable price for regulator-defensible history.
- Index strategy: `(partner_id, superseded_at)` partial-indexed `WHERE superseded_at IS NULL` for fast current-view reads.
- Replaces the existing `effective_from / effective_to` columns in PartnerEntity (Slice 1 migration: rename → add second pair → backfill `recorded_at=created_at`).
- Repositories expose `findCurrent(id)` and `findAsOf(id, validAt, recordedAt)`; default `findById` returns the current view.
- Audit log (ADR-007) records `BEFORE` and `AFTER` rows by their `recorded_at` so the two systems agree.
