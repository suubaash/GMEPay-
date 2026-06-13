# ADR-007 — Audit log: three-tier with hash chain

**Status:** Accepted (user decision, 2026-06-11)
**Slice:** Partner Setup Slice 1 (foundation) + system-wide audit pattern

## Context
Every Partner write (and every other regulated aggregate — Rule, fee schedule, bank account, settlement instruction) must produce a regulator-defensible audit trail: tamper-evident, append-only, queryable for at least 5 years, with detectable mutation. A single store cannot satisfy "tamper-evident" (writable Postgres can be silently rewritten), "queryable" (object storage is slow), and "10-year retention" (hot Postgres at 10yr is expensive) simultaneously.

## Decision
**Three tiers**:
1. **Hot — dedicated `audit` PostgreSQL database.** Separate from any service's operational DB. Each service writes via a dedicated DB role with INSERT-only privilege (UPDATE/DELETE revoked at the role level). Every row carries `prev_hash` and `row_hash = sha256(prev_hash || canonicalised_row_bytes)` so an inserted row mid-history fails verification.
2. **Stream — Kafka topic `gmepay.audit.<aggregate>`.** Every service publishes audit events via the lib-events outbox (ADR-001) in the same transaction as the business write.
3. **Cold — MinIO bucket `gmepay-audit-archive` (object-lock, 10-year retention, ADR-006 pattern).** Kafka Connect S3 sink writes audit-topic batches to JSONL files. Cold archive is the regulator-defensible store; hot DB is for query speed.

## Consequences
- One extra Postgres DB to operate (audit). Same instance, separate logical DB + role.
- One extra Kafka topic per regulated aggregate; standard outbox plumbing.
- One Kafka Connect S3 sink connector (config-only, no new infra).
- Hash chain catches silent edits; PIT recovery from cold tier is possible via Kafka offset replay or MinIO file scan.
- All services adopt a `lib-audit` library (small, sits on lib-events + lib-errors) so the row hashing + publication is uniform.
