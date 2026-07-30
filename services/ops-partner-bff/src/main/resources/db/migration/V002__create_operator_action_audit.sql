-- V002 - durable operator-action audit trail (who paused the platform / suspended which partner).
--
-- WHAT WAS WRONG. OperatorActionAuditClient had exactly two implementations: a REST client gated on
-- gmepay.operator-action-audit.client=rest, and StubOperatorActionAuditClient carrying
-- @ConditionalOnProperty(matchIfMissing = true). That selector is set in NO values file, NO compose
-- service and NO properties file, so the STUB was the live bean in every environment -- a
-- CopyOnWriteArrayList plus an AtomicLong minting "OA-1", "OA-2", ... So:
--
--   * every replica minted the same ids independently => colliding operator-action audit ids;
--   * the record of who paused the platform was split across replicas and lost on restart;
--   * recordDurable() -- the FAIL-CLOSED write that is supposed to block a money/state-affecting
--     operator action when it has no audit trail -- could never fail, so the guarantee was
--     decorative. An in-memory list is not a durable audit record.
--
-- The T1-1 fix (invert the default so the real client wins when the selector is absent) could NOT be
-- applied as-is here, because the "real" client POSTs to auth-identity
-- POST /v1/audit/operator-actions AND THAT ENDPOINT DOES NOT EXIST -- not in auth-identity, not
-- anywhere in this repo (grep-verified: the only /v1/audit surfaces are config-registry's READ
-- endpoints, AuditLogController + AuditIntegrityController). Flipping the default to it would have
-- turned every audited operator action into a 500 via recordDurable's fail-closed path. T1-1's own
-- lesson was to verify the endpoint before inverting the default; verifying it is what found this.
--
-- So the real implementation is the one that can exist today: write the record HERE, durably, in the
-- service that produces it. That is precisely what payment-executor did for the emitter half of
-- T3-3 (V006 ops_alerts) when the consumer side was not real yet -- persist where it is raised
-- rather than depend on infrastructure that does not exist.
--
-- NOT the regulator-grade audit log. config-registry owns the hash-chained audit_log (with
-- GET /v1/audit + chain verification) and remains the eventual home for these rows: this table is a
-- flat append-only log with no prev_hash chain, so it proves WHAT an operator did, not that nobody
-- edited the table afterwards. Moving it behind a config-registry write endpoint is recorded as a
-- follow-up in Documentation/GAP_REGISTER.md; until then rest/ remains selectable for the day that
-- endpoint ships.
--
-- Engine-neutral (PostgreSQL 16 / H2 in PostgreSQL mode). Append-only: no UPDATE path exists.

CREATE TABLE IF NOT EXISTS operator_action_audit (
    -- Fleet-wide unique, from one database sequence. This is the whole point: the id the operator
    -- sees ("OA-<id>") can no longer collide with an id minted by another replica.
    id          BIGSERIAL     PRIMARY KEY,
    -- Dotted resource verb: ops.pause / ops.resume / ops.maintenance / partner.suspend /
    -- transaction.resolve / webhook.replay / settlement.recon.rerun / ops.alert.ack.
    action      VARCHAR(64)   NOT NULL,
    -- The entity acted on: partner code, txn ref, delivery id, alert seq, or 'system'.
    target      VARCHAR(255)  NOT NULL,
    -- The operator principal, resolved from the access token subject (never the request header
    -- alone -- T0-3). 'unknown' only when no principal could be resolved at all.
    actor       VARCHAR(128)  NOT NULL,
    -- Operator-supplied free-text reason; nullable, because not every audited action demands one.
    reason      VARCHAR(1024),
    recorded_at TIMESTAMP     NOT NULL
);

-- The two ways this table is read by a human after an incident: "what happened, newest first" and
-- "everything that touched THIS partner / txn".
CREATE INDEX IF NOT EXISTS ix_operator_action_audit_recorded_at ON operator_action_audit (recorded_at DESC);
CREATE INDEX IF NOT EXISTS ix_operator_action_audit_target      ON operator_action_audit (target, recorded_at DESC);
