# ADR-005 — Elasticsearch role: log store (ELK) first, transaction search later

**Status:** Accepted by default (uncontested — flag to user before building phase 2 of this)
**Ticket:** 18.7-G01

## Context
The tile board lists **Elasticsearch**; the architecture diagram shows **ELK** in the observability lane. Two possible roles: centralized log search, and admin-UI transaction search.

## Decision
1. **R5 (now-planned):** Elasticsearch as the log store of the ELK stack (Filebeat/Logstash → ES → Kibana) — observability only.
2. **Deferred option:** a transaction search index (CQRS read model fed from Kafka `payment.*` events) for admin-UI free-text search — only if PostgreSQL query performance proves insufficient at real volumes. Not scheduled in v3.

## Consequences
- No service may treat Elasticsearch as a system of record; it is rebuildable from logs/events.
- Admin transaction monitoring keeps querying `transaction-mgmt` via the BFF until the deferred option is justified by measured need.
