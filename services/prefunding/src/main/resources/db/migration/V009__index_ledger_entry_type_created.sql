-- V009: composite index for the date-ranged float-movement query (GAP T2-8).
--
-- Why: GET /v1/prefunding/{code}/movements?from=&to=&types=DEBIT,CREDIT,CAPTURE is the finance
-- read surface the cross-border three-way tie-out (T2-2) uses for leg (b). It filters on
-- (partner_id, entry_type) and ranges on created_at, then pages in created_at/id order.
--
-- V002 already ships idx_ledger_entry_partner_created (partner_id, created_at), which serves the
-- UNfiltered variant of that query. The type-filtered variant — the one the reconciliation actually
-- issues, once per settlement date, over a table that grows with every payment — would have to
-- range-scan the day and discard holds and AML counters row by row. This index puts entry_type
-- between the equality column and the range column so the filter is satisfied by the index and the
-- rows come back already ordered by created_at within each type.
--
-- Additive-only: an index, no DDL on any existing column, nothing to back out but a DROP INDEX.
-- Portable to H2 (tests) and PostgreSQL (prod) — plain multi-column btree, no vendor syntax, so
-- there are no vendor-specific migration directories to mirror (this module has only db/migration).
CREATE INDEX idx_ledger_entry_partner_type_created
    ON ledger_entry (partner_id, entry_type, created_at);
