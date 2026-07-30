-- V014 - make the idempotency key a CLAIM taken BEFORE the transaction is created.
--
-- THE DEFECT. TransactionController#create called doCreate(req) FIRST and claimed the key second:
--
--     fresh  = doCreate(req);                       // row inserted
--     winner = store.putIfAbsent(key, snapshot);    // claim attempted
--
-- Two simultaneous requests carrying the same Idempotency-Key therefore created TWO TRANSACTIONS.
-- One of them won the key and its response was returned; the loser's transaction stayed in the
-- database, orphaned, with a txn_ref no caller ever saw. Sequential retries -- the common case --
-- were always handled correctly; this is strictly the concurrent window, which is exactly the window
-- an idempotency key exists for (a client that retries because it did not get an answer often
-- retries while the first request is still in flight).
--
-- THE FIX. The claim now happens first, so the database decides who may create before anything is
-- created. A key therefore has two states, which is what this migration adds:
--
--   RESERVED  -- a caller holds the claim and is creating the transaction now. A concurrent
--                duplicate is answered 409 IDEMPOTENCY_CONFLICT: retry the SAME key and you will get
--                the winner's response. It is NOT told "created" (a lie) and it does NOT create a
--                second row.
--   COMPLETED -- the response is stored and every later duplicate replays it byte-for-byte.
--
-- WHY claim_expires_at EXISTS, AND WHY IT IS THE MONEY-SAFETY COLUMN. If a claimant dies between
-- claiming and completing (pod evicted, JVM killed), a claim with no expiry would answer every
-- retry 409 for the whole 24-hour replay window -- and a payment that can never be retried is a LOST
-- payment, which is worse than the duplicate this migration set out to prevent. So a RESERVED claim
-- lapses after gmepay.idempotency.claim-ttl (engineering default 2 minutes; it must comfortably
-- exceed p99 create latency, and an owner should confirm it against real numbers) and the next
-- caller may take it over.
--
-- That reclaim is deliberately a SMALL RESIDUAL RISK, not a hidden one: if the dead claimant had
-- already inserted its transaction, the reclaiming caller creates a second one. The DB-level unique
-- index added in V015 is what catches that -- the two halves of this fix are designed together.
--
-- response_snapshot STAYS NOT NULL and a RESERVED row carries '' rather than the column being made
-- nullable: "ALTER COLUMN ... DROP NOT NULL" is not portable across PostgreSQL and H2-in-PG-mode,
-- and state is the authoritative discriminator anyway. Reads only ever return a snapshot for a
-- COMPLETED row, so the empty string is never observable as a response.
--
-- Additive and engine-neutral. Existing rows are COMPLETED, which is what they are.

ALTER TABLE idempotency_keys
    ADD COLUMN IF NOT EXISTS state VARCHAR(16) DEFAULT 'COMPLETED' NOT NULL;

ALTER TABLE idempotency_keys
    ADD COLUMN IF NOT EXISTS claim_expires_at TIMESTAMP;

-- A typo'd state must not be storable: the whole control is "which of these two states is this key
-- in", and a third value would read as neither.
ALTER TABLE idempotency_keys
    ADD CONSTRAINT ck_idempotency_keys_state CHECK (state IN ('RESERVED', 'COMPLETED'));

-- Lets the reaper find lapsed claims without scanning live keys. The point lookups still use the
-- primary key.
CREATE INDEX IF NOT EXISTS ix_idempotency_keys_claim_expires_at
    ON idempotency_keys (state, claim_expires_at);
