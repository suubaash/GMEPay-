-- V044 (POSTGRESQL VENDOR VARIANT): make audit_log append-only AT THE DATABASE, not by
-- application discipline (gap T5-1).
--
-- ENGINE SPLIT (same mechanism as V004 / V023)
-- -------------------------------------------
-- This file is the PostgreSQL twin of db/vendor/h2/V044__audit_log_append_only.sql. Unlike
-- V004 and V023 the two variants are NOT near-identical: H2 cannot express a trigger body
-- in SQL at all (its CREATE TRIGGER takes a Java class name), so the H2 twin is a
-- deliberate no-op with the reasoning written out. Read that file before assuming the
-- protection exists in a local H2 run — it does not, and production is PostgreSQL.
--
-- WHAT V006 ADMITTED
-- ------------------
-- V006's own header says the append-only property "lives in the application layer alone"
-- and that role-level revocation "comes in Slice 8 hardening". The CISO audit found that
-- Slice 8 never happened: a repo-wide grep for REVOKE / GRANT / CREATE ROLE / TRIGGER
-- across every *.sql returned ZERO hits, and compose runs config-registry as the schema
-- OWNER (`gmepay`). So the entire tamper-evidence story rested on "our code does not issue
-- UPDATEs" — which says nothing about anyone holding the connection string.
--
-- WHAT THIS MIGRATION DOES AND DOES NOT GIVE YOU
-- ---------------------------------------------
-- DOES: any UPDATE or DELETE against audit_log — from application code, from psql, from a
-- migration, from an ORM flush bug — raises an exception and aborts the statement's
-- transaction. TRUNCATE is covered separately (a TRUNCATE trigger is statement-level and
-- fires even though it deletes no rows through the row path).
--
-- DOES NOT: stop a superuser or the table owner from dropping the trigger first
-- (`ALTER TABLE audit_log DISABLE TRIGGER ...`) and then editing. A trigger is a
-- deterrent-plus-forensic-marker against accidental and casual tampering, not write-once
-- storage. Real WORM requires:
--   (a) a non-owner application role with INSERT+SELECT only and no ALTER on the table
--       (revoking UPDATE/DELETE is meaningless while the app connects as the owner —
--       ownership implies the privilege), and
--   (b) an off-box copy the DB role cannot reach at all: ADR-007 tier 3, the object-locked
--       MinIO archive with retention. NOT DEPLOYED — config-registry has no
--       SPRING_KAFKA_BOOTSTRAP_SERVERS in docker-compose.yml, so tier 2 (the Kafka
--       fan-out) does not run either and NO off-box copy of the audit log exists.
-- Both are infrastructure, tracked as T1-6 / T5-1's residual. This migration is the part
-- that CAN be done with existing means, and it is deliberately not described as more.
--
-- ADR-013: no data change, no column change — a trigger is additive and reversible.

-- The guard function. SECURITY INVOKER (the default) is correct: we are not granting
-- anything, only refusing.
CREATE OR REPLACE FUNCTION audit_log_forbid_mutation()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'audit_log is append-only (ADR-007 / gap T5-1): % on row id=% is refused. '
        'An audit row is the record of what happened; correcting it means APPENDING a '
        'corrective event, never editing history. If a schema change genuinely must touch '
        'these rows, drop this trigger explicitly in a migration that says why — so the '
        'edit is itself on the record.',
        TG_OP,
        COALESCE(OLD.id, -1)
        USING ERRCODE = 'restrict_violation';
END;
$$;

COMMENT ON FUNCTION audit_log_forbid_mutation() IS
    'Refuses UPDATE/DELETE on audit_log. See db/vendor/postgresql/V044 for what this does '
    'and does not guarantee (it is not WORM).';

-- Row-level guard for UPDATE and DELETE.
DROP TRIGGER IF EXISTS trg_audit_log_append_only ON audit_log;
CREATE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW
EXECUTE FUNCTION audit_log_forbid_mutation();

-- Statement-level guard for TRUNCATE: the row trigger above never fires for TRUNCATE, so
-- without this the single most destructive operation available would be the one that got
-- through.
CREATE OR REPLACE FUNCTION audit_log_forbid_truncate()
    RETURNS TRIGGER
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'audit_log is append-only (ADR-007 / gap T5-1): TRUNCATE is refused.'
        USING ERRCODE = 'restrict_violation';
END;
$$;

DROP TRIGGER IF EXISTS trg_audit_log_no_truncate ON audit_log;
CREATE TRIGGER trg_audit_log_no_truncate
    BEFORE TRUNCATE ON audit_log
    FOR EACH STATEMENT
EXECUTE FUNCTION audit_log_forbid_truncate();

-- Belt-and-braces for the day the application stops connecting as the owner: revoking the
-- privileges is a no-op today (ownership implies them) but it is the correct grant state,
-- and it means step (a) above is a role change in the environment rather than another
-- migration. PUBLIC is revoked because a fresh PostgreSQL database grants nothing on new
-- tables to PUBLIC anyway — this makes that explicit and survives a permissive template1.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM PUBLIC;
