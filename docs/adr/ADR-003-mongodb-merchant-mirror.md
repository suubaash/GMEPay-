# ADR-003 — Keep MongoDB for the merchant/QR mirror

**Status:** Accepted (user decision, 2026-06-10)
**Ticket:** 18.7-G01

## Context
The architecture diagram's data layer includes **MongoDB**; the tile board lists only PostgreSQL. The only document-shaped dataset is the KFTC merchant master mirror served by `merchant-qr-data` (high-read lookup by QR hash, schema drift across scheme versions).

## Decision
**Keep MongoDB**, scoped strictly to `merchant-qr-data` (merchant/QR mirror + sync staging). Everything transactional/financial stays in per-service PostgreSQL.

## Consequences
- Tickets 17.7-G01/G02 proceed as written (Mongo-backed lookup + nightly sync).
- One additional datastore to operate; acceptable because it is read-mostly and rebuildable from KFTC files (no backup-criticality).
- DB-per-service rule unchanged: no other service may read Mongo directly — only via `GET /v1/merchants/{qr}`.
