# ADR-013 — Schema migration policy: Flyway 10 + Expand / Backfill / Contract

**Status:** Accepted (user decision, 2026-06-11)
**Slice:** all slices (cross-cutting discipline)

## Context
A 20-year system runs hundreds of breaking schema changes over its life. Zero-downtime deploys are non-negotiable. Naive in-place changes (`ALTER COLUMN NOT NULL`, `DROP COLUMN`) break the previous app release while it's still rolling out — at best a 10-minute outage, at worst data loss when the old version writes a NULL into the new NOT NULL column. Flyway 10 (already chosen) gives ordered, idempotent migrations; the discipline change is in how migrations are *authored*.

## Decision
Every breaking schema change is shipped as **three releases** following the Expand / Backfill / Contract pattern:

| Step | Migration script | App release | What happens |
|---|---|---|---|
| Expand | `Vnnn__add_<col>.sql` | release N | add column nullable / add new table / add new constraint as `NOT VALID` |
| Coexist | (no migration) | release N — writes new column, reads old + new | both shapes valid |
| Backfill | `Vnnn+1__backfill_<col>.sql` | release N+1 | idempotent Spring Batch job populates new column; old column read still valid |
| Switch | (no migration) | release N+1 — reads new column only, writes both | new shape canonical |
| Contract | `Vnnn+2__drop_<col>.sql` | release N+2 | drop old column / `ALTER NOT NULL` / `VALIDATE CONSTRAINT` |

`spring.jpa.hibernate.ddl-auto=none` everywhere — Flyway owns the schema, not Hibernate. Codified in CI: any single PR that contains both `ALTER ... DROP COLUMN` and `ALTER ... ADD COLUMN <same_col>` is rejected.

## Consequences
- Three-release horizon for every breaking change. Slows individual changes; makes them safe.
- Backfill jobs are idempotent Spring Batch tasks (existing pattern in `revenue-ledger`).
- Effective-dated tables (ADR-010) follow the same pattern but additionally bump `superseded_at` rather than mutating the row.
- Pre-merge CI check rejects bad patterns: in-place `NOT NULL` add on existing table, `DROP COLUMN` without its `ADD COLUMN` two releases prior, missing `IF NOT EXISTS` on idempotency-critical migrations.
