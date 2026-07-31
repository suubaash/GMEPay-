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

## Amendment 2026-07-28 — chain version 2, and what is actually deployed (gap T5-1)

The decision above stands; two things about it were wrong in implementation and one was never built.
Recorded here so the ADR does not read as a description of reality it never had.

**1. `canonicalised_row_bytes` was too narrow.** The as-built canonicalisation covered
`event_type | actor_id | recorded_at | before_jsonb | after_jsonb` and therefore left
`aggregate_type`, `aggregate_id` and `actor_ip` **outside** the digest — those columns could be
rewritten in place and verification still passed. `HashChain.CHAIN_V2` seals them, plus the version
number itself so a v2 row cannot be downgraded to verify under the weaker v1 digest. Rows written
before the change are **not** re-sealed: a bulk re-hash would make an honest migration
indistinguishable from an attacker who rewrote the log and recomputed the hashes. Each row carries
`chain_version`, verification canonicalises per-row, and the verifier reports how many rows remain on
v1 so the residual exposure is a number rather than an assumption.

**2. "INSERT-only privilege (UPDATE/DELETE revoked at the role level)" was never implemented.** No
`REVOKE`, `GRANT`, `CREATE ROLE` or `TRIGGER` statement existed anywhere in the repo, and the
application connects as the schema **owner**, for which revoking those privileges is a no-op.
`config-registry V044` now installs `BEFORE UPDATE / DELETE / TRUNCATE` triggers that refuse the
statement (PostgreSQL; H2 cannot express a SQL trigger body and its vendor twin is a documented
no-op). That is a deterrent and a forensic marker, **not** the role-level control this ADR
specifies — an owner can disable a trigger. The unbuilt part of tier 1 is therefore a non-owner
application role, tracked under T5-1's residual and T1-6.

**3. Tiers 2 and 3 are not deployed.** config-registry has no `SPRING_KAFKA_BOOTSTRAP_SERVERS`, so
no audit topic is produced and the MinIO object-locked archive — which this ADR names as *the*
regulator-defensible store — does not exist. Today the hot DB is the only copy, i.e. the platform is
running on the one tier this ADR says is for query speed rather than defensibility. Wiring is in
place (`AuditConfig` uses the Kafka publisher as soon as bootstrap-servers is set), so it is a
deployment change, not a code change.

**4. Actor identity.** ADR-008's `'system'` carve-out made the bare literal `"system"` a legal actor.
Because it was also the silent default for a missing `X-Actor` header, it could not mean anything;
`AuditActors` now refuses it on the write path. Genuine platform actions use
`system:<component>`; unproven claims are recorded as `unverified:<claim>` and are structurally
distinguishable from a verified principal.

## Consequences
- One extra Postgres DB to operate (audit). Same instance, separate logical DB + role.
- One extra Kafka topic per regulated aggregate; standard outbox plumbing.
- One Kafka Connect S3 sink connector (config-only, no new infra).
- Hash chain catches silent edits; PIT recovery from cold tier is possible via Kafka offset replay or MinIO file scan.
- All services adopt a `lib-audit` library (small, sits on lib-events + lib-errors) so the row hashing + publication is uniform.
