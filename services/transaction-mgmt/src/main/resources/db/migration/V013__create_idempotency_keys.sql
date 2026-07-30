-- V013 — durable idempotency-key store (replica-ceiling closure).
--
-- WHY A TABLE AND NOT REDIS.
-- The idempotency key is the only thing standing between a partner retry and a SECOND MONEY
-- TRANSACTION. It was held in a per-JVM ConcurrentHashMap (in-memory fallback) or in Redis
-- (selected whenever spring.data.redis.host was set, which the Helm ABI ConfigMap does
-- fleet-wide). Both are wrong for different reasons:
--
--   * per-JVM  -> N replicas give the same key an independent 24h window each, so one retry
--                 landing on another pod creates a second transaction. This is what held
--                 transaction-mgmt at one replica.
--   * Redis    -> shared, but NOT durable in this deployment (redis:7-alpine, no AOF, no
--                 replication). A Redis restart empties the window silently and a retry after it
--                 creates the second transaction anyway -- the same failure, just rarer and harder
--                 to reproduce. It also introduces a dependency that can be UP or DOWN
--                 independently of the money path, which forces a fail-open/fail-closed choice on
--                 a duplicate-suppression control where neither answer is good.
--
-- This module already "does durability" with PostgreSQL + Flyway + ShedLock; the transaction the
-- key protects is written to this very database. Putting the key here means it cannot be
-- unavailable while the money path is available: if this table cannot be read, no transaction can
-- be created either, so there is no window in which duplicates are possible.
--
-- Engine-neutral (PostgreSQL + H2 in PostgreSQL mode): VARCHAR / TEXT / TIMESTAMP only. Additive.
--
-- RETENTION: expires_at is enforced on read (an expired row is treated as absent) AND swept by
-- IdempotencyRetentionSweeper, so the table does not grow without bound. The index exists for the
-- sweeper's range delete, not for the point lookups, which use the primary key.

CREATE TABLE IF NOT EXISTS idempotency_keys (
    -- The client-supplied Idempotency-Key, verbatim. PRIMARY KEY is the claim mechanism: the
    -- database, not the application, decides which concurrent duplicate wins.
    idempotency_key   VARCHAR(255) NOT NULL,
    -- The first response, serialized. Replayed byte-for-byte to every later duplicate.
    response_snapshot TEXT         NOT NULL,
    created_at        TIMESTAMP    NOT NULL,
    -- created_at + the 24h replay window. A key may be legitimately reused after this.
    expires_at        TIMESTAMP    NOT NULL,
    PRIMARY KEY (idempotency_key)
);

CREATE INDEX IF NOT EXISTS ix_idempotency_keys_expires_at
    ON idempotency_keys (expires_at);
